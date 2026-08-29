import { randomUUID } from 'node:crypto';
import type { OmadaAdapter } from './adapter.ts';
import { QuotaExceededError, BackendUnavailableError } from './adapter.ts';
import type { Voucher, CreateVoucherInput } from '../types.ts';
import { getSite, getPlan } from '../config.ts';
import { now } from '../clock.ts';

/** Omada voucher codes are digits only, which is what guests actually type. */
function makeCode(length: number): string {
  let out = '';
  for (let i = 0; i < length; i++) out += Math.floor(Math.random() * 10);
  return out;
}

/**
 * In-memory stand-in for a real Omada controller. It enforces the same things
 * that bite in production: the per-site voucher ceiling, unused-voucher expiry,
 * and session countdown from first login.
 */
export class MockOmadaAdapter implements OmadaAdapter {
  readonly name = 'mock';
  /** Flip to simulate an internet/controller outage and exercise the fallback. */
  offline = false;
  private store = new Map<string, Voucher[]>();

  private bucket(siteId: string): Voucher[] {
    let b = this.store.get(siteId);
    if (!b) {
      b = [];
      this.store.set(siteId, b);
    }
    return b;
  }

  /** Roll statuses forward to whatever the clock now says. */
  private age(siteId: string): Voucher[] {
    const t = now();
    for (const v of this.bucket(siteId)) {
      if (v.status === 'unused' && t > v.validUntil) v.status = 'expired';
      else if (v.status === 'in_use' && v.usedUntil !== null && t > v.usedUntil) {
        v.status = 'expired';
      }
    }
    return this.bucket(siteId);
  }

  private guardOnline(): void {
    if (this.offline) {
      throw new BackendUnavailableError('Mock controller is offline');
    }
  }

  async createVouchers(siteId: string, input: CreateVoucherInput): Promise<Voucher[]> {
    this.guardOnline();
    const site = getSite(siteId);
    const plan = getPlan(siteId, input.planId);
    if (!site) throw new Error(`Unknown site ${siteId}`);
    if (!plan) throw new Error(`Unknown plan ${input.planId} for site ${siteId}`);

    const bucket = this.age(siteId);
    if (bucket.length + input.count > site.voucherCap) {
      throw new QuotaExceededError(siteId, site.voucherCap);
    }

    const t = now();
    const created: Voucher[] = [];
    for (let i = 0; i < input.count; i++) {
      const voucher: Voucher = {
        id: randomUUID(),
        code: makeCode(plan.codeLength),
        siteId,
        planId: plan.id,
        status: 'unused',
        createdAt: t,
        validUntil: t + plan.unusedValidDays * 86_400_000,
        durationMinutes: plan.durationMinutes,
        firstUsedAt: null,
        usedUntil: null,
        note: input.note,
      };
      bucket.push(voucher);
      created.push(voucher);
    }
    return created;
  }

  async listVouchers(siteId: string): Promise<Voucher[]> {
    this.guardOnline();
    return this.age(siteId).map((v) => ({ ...v }));
  }

  async deleteVouchers(siteId: string, voucherIds: string[]): Promise<number> {
    this.guardOnline();
    const ids = new Set(voucherIds);
    const bucket = this.bucket(siteId);
    const kept = bucket.filter((v) => !ids.has(v.id));
    const removed = bucket.length - kept.length;
    this.store.set(siteId, kept);
    return removed;
  }

  async countVouchers(siteId: string): Promise<number> {
    this.guardOnline();
    return this.age(siteId).length;
  }

  // --- simulation-only helpers (no equivalent on a real controller) ---

  /** Simulate a guest typing the code into the captive portal. */
  redeem(siteId: string, code: string): Voucher | undefined {
    const v = this.age(siteId).find((x) => x.code === code);
    if (!v || v.status !== 'unused') return undefined;
    const t = now();
    v.status = 'in_use';
    v.firstUsedAt = t;
    v.usedUntil = t + v.durationMinutes * 60_000;
    return v;
  }

  /** Bulk-load a site with vouchers to test behaviour near the cap. */
  seed(siteId: string, vouchers: Voucher[]): void {
    this.bucket(siteId).push(...vouchers);
  }
}
