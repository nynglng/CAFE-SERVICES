import { readFileSync, writeFileSync, existsSync, mkdirSync, renameSync } from 'node:fs';
import { dirname } from 'node:path';
import type { Issuance, Voucher } from '../types.ts';

/**
 * Our own record of what was sold and what is sitting in the fallback pool.
 * Deliberately independent of the controller: when the janitor deletes a spent
 * voucher upstream, the sale it belonged to is still auditable here.
 *
 * A JSON file is enough at cafe volume (a few hundred rows a day) and keeps the
 * service dependency-free. Swap this module for SQLite if a site ever outgrows it.
 */

type LedgerFile = {
  issuances: Issuance[];
  /** Pre-made codes held back for when the controller is unreachable. */
  pool: Voucher[];
};

/** LEDGER_PATH lets tests (and a second site on one box) use their own file. */
const dataPath =
  process.env.LEDGER_PATH ??
  new URL('../../data/ledger.json', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1');

let state: LedgerFile = { issuances: [], pool: [] };
let dirty = false;

export function load(): void {
  if (existsSync(dataPath)) {
    try {
      state = JSON.parse(readFileSync(dataPath, 'utf8')) as LedgerFile;
      state.issuances ??= [];
      state.pool ??= [];
    } catch {
      // A corrupt ledger must not stop the counter from selling.
      state = { issuances: [], pool: [] };
    }
  }
}

export function save(): void {
  if (!dirty) return;
  mkdirSync(dirname(dataPath), { recursive: true });
  const tmp = `${dataPath}.tmp`;
  writeFileSync(tmp, JSON.stringify(state, null, 2), 'utf8');
  renameSync(tmp, dataPath);
  dirty = false;
}

function touch(): void {
  dirty = true;
}

// --- issuances ---

export function addIssuance(issuance: Issuance): void {
  state.issuances.push(issuance);
  touch();
}

export function findIssuance(issuanceId: string): Issuance | undefined {
  return state.issuances.find((i) => i.issuanceId === issuanceId);
}

/** Idempotency: the same POS key must never mint a second voucher. */
export function findByIdempotencyKey(siteId: string, key: string): Issuance | undefined {
  return state.issuances.find((i) => i.siteId === siteId && i.idempotencyKey === key);
}

export function findByReference(siteId: string, reference: string): Issuance[] {
  return state.issuances.filter((i) => i.siteId === siteId && i.reference === reference);
}

export function recordReprint(issuanceId: string): Issuance | undefined {
  const found = findIssuance(issuanceId);
  if (found) {
    found.reprints++;
    touch();
  }
  return found;
}

export function issuances(siteId?: string): Issuance[] {
  return siteId ? state.issuances.filter((i) => i.siteId === siteId) : state.issuances;
}

// --- fallback pool ---

export function poolFor(siteId: string, planId: string): Voucher[] {
  return state.pool.filter((v) => v.siteId === siteId && v.planId === planId);
}

export function poolAdd(vouchers: Voucher[]): void {
  state.pool.push(...vouchers);
  touch();
}

/** Remove and return one spare code, or undefined if the reserve is empty. */
export function poolTake(siteId: string, planId: string): Voucher | undefined {
  const idx = state.pool.findIndex((v) => v.siteId === siteId && v.planId === planId);
  if (idx === -1) return undefined;
  const [taken] = state.pool.splice(idx, 1);
  touch();
  return taken;
}

export function poolDrop(voucherIds: string[]): number {
  const ids = new Set(voucherIds);
  const before = state.pool.length;
  state.pool = state.pool.filter((v) => !ids.has(v.id));
  if (state.pool.length !== before) touch();
  return before - state.pool.length;
}

export function poolAll(): Voucher[] {
  return state.pool;
}
