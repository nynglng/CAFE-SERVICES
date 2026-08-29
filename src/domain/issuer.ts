import { randomUUID } from 'node:crypto';
import { adapter } from '../omada/index.ts';
import { QuotaExceededError, BackendUnavailableError } from '../omada/adapter.ts';
import { getPlan, getSite } from '../config.ts';
import * as ledger from '../store/ledger.ts';
import * as pool from './pool.ts';
import * as janitor from './janitor.ts';
import { buildSlip } from '../print/slip.ts';
import type { Issuance, Voucher, IssueSource } from '../types.ts';
import type { Slip } from '../print/slip.ts';
import { now } from '../clock.ts';

/** No voucher could be produced by any route — the counter must be told. */
export class IssueFailedError extends Error {
  cause: string;
  constructor(message: string, cause: string) {
    super(message);
    this.name = 'IssueFailedError';
    this.cause = cause;
  }
}

export type IssueRequest = {
  siteId: string;
  planId: string;
  reference?: string | null;
  idempotencyKey?: string | null;
};

export type IssueResult = {
  issuance: Issuance;
  voucher: Voucher;
  source: IssueSource;
  replayed: boolean;
  slip: Slip;
  notes: string[];
};

/**
 * Hybrid issuing, in priority order:
 *   1. replay  - same idempotency key already sold, hand back the same code
 *   2. live    - mint a fresh voucher on the controller (the normal path)
 *   3. sweep   - if the site is at its cap, purge spent vouchers and retry once
 *   4. pool    - if the controller is unreachable, hand out a reserved code
 * Only when all four fail does the sale get an error.
 */
export async function issue(req: IssueRequest): Promise<IssueResult> {
  const site = getSite(req.siteId);
  const plan = getPlan(req.siteId, req.planId);
  if (!site) throw new IssueFailedError(`Unknown site ${req.siteId}`, 'unknown_site');
  if (!plan) throw new IssueFailedError(`Unknown plan ${req.planId}`, 'unknown_plan');

  const notes: string[] = [];

  // 1. Replay — a POS retry after a timeout must not mint a second code.
  if (req.idempotencyKey) {
    const prior = ledger.findByIdempotencyKey(req.siteId, req.idempotencyKey);
    if (prior) {
      const voucher = reconstruct(prior, plan.durationMinutes);
      return {
        issuance: prior,
        voucher,
        source: prior.source,
        replayed: true,
        slip: buildSlip(site, plan, voucher, prior),
        notes: ['replayed existing issuance for this idempotency key'],
      };
    }
  }

  const note = `pos:${req.reference ?? 'walk-in'}`;
  let voucher: Voucher | undefined;
  let source: IssueSource = 'live';

  try {
    // 2. Normal path.
    [voucher] = await adapter.createVouchers(req.siteId, {
      planId: plan.id,
      count: 1,
      note,
    });
  } catch (err) {
    if (err instanceof QuotaExceededError) {
      // 3. At the ceiling: clear the dead weight and try once more.
      notes.push('site was at its voucher cap; ran a cleanup sweep');
      const report = await janitor.sweep(req.siteId);
      notes.push(
        `sweep freed ${report.deletedExpired + report.deletedOverflow} slots ` +
          `(${report.after}/${report.cap} in use)`,
      );
      try {
        [voucher] = await adapter.createVouchers(req.siteId, {
          planId: plan.id,
          count: 1,
          note,
        });
      } catch (retryErr) {
        voucher = fallback(req, notes, retryErr as Error);
        source = 'pool';
      }
    } else if (err instanceof BackendUnavailableError) {
      // 4. Controller unreachable.
      voucher = fallback(req, notes, err);
      source = 'pool';
    } else {
      throw new IssueFailedError((err as Error).message, 'backend_error');
    }
  }

  if (!voucher) {
    throw new IssueFailedError('No voucher could be issued', 'exhausted');
  }

  const issuance: Issuance = {
    issuanceId: randomUUID(),
    siteId: req.siteId,
    planId: plan.id,
    voucherId: voucher.id,
    code: voucher.code,
    source,
    reference: req.reference ?? null,
    idempotencyKey: req.idempotencyKey ?? null,
    issuedAt: now(),
    reprints: 0,
  };
  ledger.addIssuance(issuance);
  ledger.save();

  // Keep the reserve stocked without making the customer wait for it.
  void pool.refillSite(req.siteId).catch(() => {});

  return {
    issuance,
    voucher,
    source,
    replayed: false,
    slip: buildSlip(site, plan, voucher, issuance),
    notes,
  };
}

function fallback(req: IssueRequest, notes: string[], err: Error): Voucher {
  const spare = pool.take(req.siteId, req.planId);
  if (!spare) {
    throw new IssueFailedError(
      `Controller unavailable and no reserve codes left for ${req.planId}`,
      'no_reserve',
    );
  }
  notes.push(`controller unavailable (${err.message}); issued from reserve pool`);
  return spare;
}

/** Rebuild a printable voucher view from a ledger row after the fact. */
function reconstruct(issuance: Issuance, durationMinutes: number): Voucher {
  return {
    id: issuance.voucherId,
    code: issuance.code,
    siteId: issuance.siteId,
    planId: issuance.planId,
    status: 'unused',
    createdAt: issuance.issuedAt,
    validUntil: issuance.issuedAt,
    durationMinutes,
    firstUsedAt: null,
    usedUntil: null,
    note: '',
  };
}

/** Reprint the same code — never mints a new voucher. */
export function reprint(issuanceId: string): IssueResult | undefined {
  const issuance = ledger.recordReprint(issuanceId);
  if (!issuance) return undefined;
  const site = getSite(issuance.siteId);
  const plan = getPlan(issuance.siteId, issuance.planId);
  if (!site || !plan) return undefined;
  ledger.save();
  const voucher = reconstruct(issuance, plan.durationMinutes);
  return {
    issuance,
    voucher,
    source: issuance.source,
    replayed: true,
    slip: buildSlip(site, plan, voucher, issuance),
    notes: [`reprint #${issuance.reprints}`],
  };
}
