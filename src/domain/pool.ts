import { adapter } from '../omada/index.ts';
import { getSite, getPlan } from '../config.ts';
import * as ledger from '../store/ledger.ts';
import type { Voucher } from '../types.ts';

/**
 * The offline reserve. A handful of codes per plan, created ahead of time, so a
 * dead internet link or an unreachable controller never stops the counter from
 * selling. These are real vouchers and do count against the site cap, so keep
 * poolTarget small (single digits).
 */

export type PoolStatus = { planId: string; have: number; target: number };

export function status(siteId: string): PoolStatus[] {
  const site = getSite(siteId);
  if (!site) return [];
  return site.plans.map((plan) => ({
    planId: plan.id,
    have: ledger.poolFor(siteId, plan.id).length,
    target: plan.poolTarget,
  }));
}

export function take(siteId: string, planId: string): Voucher | undefined {
  return ledger.poolTake(siteId, planId);
}

/** Top one plan back up to its target. Safe to call often; no-ops when full. */
export async function refillPlan(siteId: string, planId: string): Promise<number> {
  const plan = getPlan(siteId, planId);
  if (!plan) return 0;
  const missing = plan.poolTarget - ledger.poolFor(siteId, planId).length;
  if (missing <= 0) return 0;

  const created = await adapter.createVouchers(siteId, {
    planId,
    count: missing,
    note: `pool:${siteId}:${planId}`,
  });
  ledger.poolAdd(created);
  ledger.save();
  return created.length;
}

/** Top every plan of a site back up. Failures are swallowed per plan. */
export async function refillSite(siteId: string): Promise<Record<string, number>> {
  const site = getSite(siteId);
  const out: Record<string, number> = {};
  if (!site) return out;
  for (const plan of site.plans) {
    try {
      out[plan.id] = await refillPlan(siteId, plan.id);
    } catch {
      // Refilling is best-effort: if the controller is down the reserve is
      // exactly what we fall back on, and it gets topped up when it returns.
      out[plan.id] = 0;
    }
  }
  return out;
}
