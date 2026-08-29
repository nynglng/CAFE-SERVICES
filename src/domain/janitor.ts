import { adapter } from '../omada/index.ts';
import { getSite, config, sites } from '../config.ts';
import * as ledger from '../store/ledger.ts';
import { now } from '../clock.ts';

/**
 * The chore this whole service exists to kill: deleting spent vouchers so the
 * site never hits its stored-voucher ceiling.
 *
 * Two passes:
 *   1. Always remove vouchers the controller considers dead (expired, or a
 *      finished session). These can never be redeemed again.
 *   2. If the site is still above its high-water mark, shed the oldest
 *      still-counting vouchers, oldest first, until it drops back under.
 */

export type SweepReport = {
  siteId: string;
  before: number;
  deletedExpired: number;
  deletedOverflow: number;
  after: number;
  cap: number;
  highWater: number;
  error?: string;
};

export async function sweep(siteId: string): Promise<SweepReport> {
  const site = getSite(siteId);
  if (!site) throw new Error(`Unknown site ${siteId}`);

  const highWater = Math.floor(site.voucherCap * config.janitorHighWater);
  const report: SweepReport = {
    siteId,
    before: 0,
    deletedExpired: 0,
    deletedOverflow: 0,
    after: 0,
    cap: site.voucherCap,
    highWater,
  };

  let vouchers;
  try {
    vouchers = await adapter.listVouchers(siteId);
  } catch (err) {
    report.error = (err as Error).message;
    return report;
  }
  report.before = vouchers.length;

  const t = now();
  const dead = vouchers.filter(
    (v) =>
      v.status === 'expired' ||
      (v.status === 'unused' && v.validUntil < t) ||
      (v.status === 'in_use' && v.usedUntil !== null && v.usedUntil < t),
  );

  if (dead.length > 0) {
    report.deletedExpired = await adapter.deleteVouchers(
      siteId,
      dead.map((v) => v.id),
    );
    ledger.poolDrop(dead.map((v) => v.id));
  }

  let remaining = report.before - report.deletedExpired;

  if (remaining > highWater) {
    const deadIds = new Set(dead.map((v) => v.id));
    // Never shed a code that has not been handed to a customer yet.
    const poolIds = new Set(ledger.poolAll().map((v) => v.id));
    const shedable = vouchers
      .filter((v) => !deadIds.has(v.id) && !poolIds.has(v.id) && v.status === 'in_use')
      .sort((a, b) => a.createdAt - b.createdAt)
      .slice(0, remaining - highWater);

    if (shedable.length > 0) {
      report.deletedOverflow = await adapter.deleteVouchers(
        siteId,
        shedable.map((v) => v.id),
      );
      remaining -= report.deletedOverflow;
    }
  }

  report.after = remaining;
  ledger.save();
  return report;
}

export async function sweepAll(): Promise<SweepReport[]> {
  const reports: SweepReport[] = [];
  for (const site of sites) {
    reports.push(await sweep(site.id));
  }
  return reports;
}

let timer: NodeJS.Timeout | null = null;

export function start(onReport?: (reports: SweepReport[]) => void): void {
  if (timer) return;
  const intervalMs = Math.max(1, config.janitorIntervalMinutes) * 60_000;
  timer = setInterval(() => {
    void sweepAll()
      .then((reports) => onReport?.(reports))
      .catch(() => {
        /* a failed sweep is retried on the next tick */
      });
  }, intervalMs);
  timer.unref();
}

export function stop(): void {
  if (timer) {
    clearInterval(timer);
    timer = null;
  }
}
