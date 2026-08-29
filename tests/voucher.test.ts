import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { rmSync } from 'node:fs';

process.env.OMADA_MODE = 'mock';
process.env.LEDGER_PATH = join(tmpdir(), `voucher-test-${process.pid}.json`);
process.env.JANITOR_HIGH_WATER = '0.75';

const { issue, reprint, IssueFailedError } = await import('../src/domain/issuer.ts');
const { adapter, mock } = await import('../src/omada/index.ts');
const pool = await import('../src/domain/pool.ts');
const janitor = await import('../src/domain/janitor.ts');
const ledger = await import('../src/store/ledger.ts');
const clock = await import('../src/clock.ts');
const { getSite } = await import('../src/config.ts');

const SITE = 'cafe-two';
const PLAN = '2h';

before(() => {
  ledger.load();
});

after(() => {
  clock.reset();
  rmSync(process.env.LEDGER_PATH!, { force: true });
});

test('a sale mints exactly one voucher on the controller', async () => {
  const before = await adapter.countVouchers(SITE);
  const result = await issue({ siteId: SITE, planId: PLAN, reference: 'T-1' });
  assert.equal(result.source, 'live');
  assert.match(result.voucher.code, /^\d{6}$/);
  // The sale plus whatever the background pool refill created.
  assert.ok((await adapter.countVouchers(SITE)) > before);
  assert.equal(ledger.findIssuance(result.issuance.issuanceId)?.code, result.voucher.code);
});

test('the same idempotency key never mints a second voucher', async () => {
  const key = 'R-9001:line1:0';
  const first = await issue({ siteId: SITE, planId: PLAN, idempotencyKey: key });
  const second = await issue({ siteId: SITE, planId: PLAN, idempotencyKey: key });
  assert.equal(second.voucher.code, first.voucher.code);
  assert.equal(second.replayed, true);
  assert.equal(ledger.issuances(SITE).filter((i) => i.idempotencyKey === key).length, 1);
});

test('a reprint returns the same code and counts the reprint', async () => {
  const sale = await issue({ siteId: SITE, planId: PLAN, reference: 'T-reprint' });
  const again = reprint(sale.issuance.issuanceId);
  assert.equal(again?.voucher.code, sale.voucher.code);
  assert.equal(again?.issuance.reprints, 1);
});

test('an offline controller falls back to the reserve pool', async () => {
  await pool.refillSite(SITE);
  const reserveBefore = pool.status(SITE).find((p) => p.planId === PLAN)!.have;
  assert.ok(reserveBefore > 0, 'pool should be stocked before the outage');

  mock!.offline = true;
  const result = await issue({ siteId: SITE, planId: PLAN, reference: 'T-offline' });
  mock!.offline = false;

  assert.equal(result.source, 'pool');
  assert.equal(pool.status(SITE).find((p) => p.planId === PLAN)!.have, reserveBefore - 1);
});

test('an outage with an empty reserve fails loudly instead of silently', async () => {
  // Drain the reserve for this plan.
  while (pool.take(SITE, PLAN)) {
    /* empty it */
  }
  mock!.offline = true;
  await assert.rejects(
    () => issue({ siteId: SITE, planId: PLAN, reference: 'T-empty' }),
    (err: unknown) => err instanceof IssueFailedError && (err as { cause: string }).cause === 'no_reserve',
  );
  mock!.offline = false;
  await pool.refillSite(SITE);
});

test('the janitor deletes spent vouchers and nothing else', async () => {
  const sale = await issue({ siteId: SITE, planId: PLAN, reference: 'T-sweep' });
  const untouched = await issue({ siteId: SITE, planId: PLAN, reference: 'T-keep' });

  mock!.redeem(SITE, sale.voucher.code);
  clock.advance(60 * 5); // past the 2 hour session

  const report = await janitor.sweep(SITE);
  assert.ok(report.deletedExpired >= 1, 'the spent voucher should be gone');

  const remaining = await adapter.listVouchers(SITE);
  assert.ok(
    !remaining.some((v) => v.code === sale.voucher.code),
    'spent voucher must be deleted',
  );
  assert.ok(
    remaining.some((v) => v.code === untouched.voucher.code),
    'an unredeemed voucher must survive',
  );
  clock.reset();
});

test('hitting the cap triggers a sweep and the sale still succeeds', async () => {
  const site = getSite(SITE)!;

  // Fill the site to its ceiling with vouchers that are already spent.
  const current = await adapter.countVouchers(SITE);
  const filler = site.voucherCap - current;
  const created = await adapter.createVouchers(SITE, {
    planId: PLAN,
    count: filler,
    note: 'test:filler',
  });
  for (const v of created) mock!.redeem(SITE, v.code);
  assert.equal(await adapter.countVouchers(SITE), site.voucherCap);

  // Age them past their session so they are genuinely dead weight.
  clock.advance(60 * 24);

  // The next sale would fail against a raw controller; here it self-heals.
  const result = await issue({ siteId: SITE, planId: PLAN, reference: 'T-cap' });
  assert.equal(result.source, 'live', 'should recover a live slot, not burn the reserve');
  assert.ok(
    result.notes.some((n) => n.includes('cleanup sweep')),
    'the sale should report that it swept to make room',
  );
  assert.ok((await adapter.countVouchers(SITE)) < site.voucherCap);
  clock.reset();
});
