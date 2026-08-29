/**
 * End-to-end walkthrough of the voucher service against the simulated
 * controller. Run it with:  npm run demo
 *
 * It plays out the day the service is meant to handle: a normal sale, a POS
 * retry, a guest burning through their hour, the automatic cleanup, a Loyverse
 * receipt webhook (including a duplicate delivery), and an internet outage.
 */

process.env.OMADA_MODE = 'mock';
process.env.API_KEY ??= 'demo-key';
process.env.LOYVERSE_WEBHOOK_SECRET ??= 'demo-secret';
process.env.PORT ??= '8799';

const { startServer } = await import('../src/server.ts');
const ledger = await import('../src/store/ledger.ts');
const pool = await import('../src/domain/pool.ts');

const KEY = process.env.API_KEY!;
const SECRET = process.env.LOYVERSE_WEBHOOK_SECRET!;
const BASE = `http://localhost:${process.env.PORT}`;
const SITE = 'cafe-one';

const server = startServer();
await new Promise((r) => setTimeout(r, 150));

function heading(step: string, title: string): void {
  console.log(`\n${'─'.repeat(64)}\n${step}  ${title}\n${'─'.repeat(64)}`);
}

async function api(method: string, path: string, body?: unknown, headers: Record<string, string> = {}) {
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: { 'content-type': 'application/json', Authorization: `Bearer ${KEY}`, ...headers },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  return { status: res.status, body: (await res.json()) as any };
}

async function showStatus(label: string): Promise<void> {
  const { body } = await api('GET', `/api/v1/sites/${SITE}/status`);
  const reserve = body.pool.map((p: any) => `${p.planId}:${p.have}/${p.target}`).join(' ');
  console.log(
    `${label}  stored ${body.storedVouchers}/${body.cap}` +
      `  (high-water ${body.highWater})  reserve[${reserve}]  sold ${body.issuedAllTime}`,
  );
}

// ---------------------------------------------------------------------------

heading('1.', 'Boot: stock the offline reserve');
await pool.refillSite(SITE);
await showStatus('   ');

heading('2.', 'Cashier rings up "WiFi 1 Hour" — POS asks for a code');
const sale = await api('POST', '/api/v1/vouchers', {
  siteId: SITE,
  planId: '1h',
  reference: 'R-1041',
  idempotencyKey: 'R-1041:line1:0',
});
console.log(`   HTTP ${sale.status}  code ${sale.body.code}  source=${sale.body.source}`);
console.log('\n   ── what prints ─────────────────');
console.log(
  sale.body.slip.text
    .split('\n')
    .map((l: string) => `   │ ${l}`)
    .join('\n'),
);

heading('3.', 'POS times out and retries the SAME request');
const retry = await api('POST', '/api/v1/vouchers', {
  siteId: SITE,
  planId: '1h',
  reference: 'R-1041',
  idempotencyKey: 'R-1041:line1:0',
});
console.log(`   code ${retry.body.code}  replayed=${retry.body.replayed}`);
console.log(
  retry.body.code === sale.body.code
    ? '   OK: no duplicate voucher was minted.'
    : '   PROBLEM: a second voucher was minted.',
);

heading('4.', 'Guest connects, uses the hour, and the voucher dies');
const redeem = await api('POST', '/api/v1/sim/redeem', { siteId: SITE, code: sale.body.code });
console.log(`   redeemed -> status=${redeem.body.status} session ends ${redeem.body.sessionEndsAt}`);
await api('POST', '/api/v1/sim/advance', { minutes: 90 });
console.log('   (clock fast-forwarded 90 minutes)');

heading('5.', 'The chore you used to do by hand');
await showStatus('   before');
const cleanup = await api('POST', '/api/v1/admin/cleanup', { siteId: SITE });
const rep = cleanup.body.reports[0];
console.log(
  `   swept: ${rep.deletedExpired} spent + ${rep.deletedOverflow} overflow removed ` +
    `-> ${rep.after}/${rep.cap}`,
);
await showStatus('   after ');

heading('6.', 'Loyverse receipt webhook: 2x WiFi + a coffee');
const receipt = {
  type: 'receipts.update',
  receipts: [
    {
      receipt_number: 'R-1042',
      line_items: [
        { id: 'li-1', sku: 'WIFI-1H', item_name: 'WiFi 1 Hour', quantity: 2 },
        { id: 'li-2', sku: 'CAFE-LATTE', item_name: 'Cafe Latte', quantity: 1 },
      ],
    },
  ],
};
const hook = await fetch(`${BASE}/webhooks/loyverse?siteId=${SITE}`, {
  method: 'POST',
  headers: { 'content-type': 'application/json', 'x-webhook-secret': SECRET },
  body: JSON.stringify(receipt),
});
const hookBody = (await hook.json()) as any;
const outcome = hookBody.outcomes[0];
console.log(`   receipt ${outcome.receiptNumber}`);
for (const i of outcome.issued) {
  console.log(
    `     issued ${i.planId}  code ${i.code}  source=${i.source}  slip->${i.print.target}`,
  );
}
for (const s of outcome.skipped) console.log(`     skipped ${s.item} (${s.reason})`);

heading('7.', 'Loyverse re-delivers the same receipt (it does this)');
const replay = await fetch(`${BASE}/webhooks/loyverse?siteId=${SITE}`, {
  method: 'POST',
  headers: { 'content-type': 'application/json', 'x-webhook-secret': SECRET },
  body: JSON.stringify(receipt),
});
const replayOutcome = ((await replay.json()) as any).outcomes[0];
const sameCodes =
  JSON.stringify(replayOutcome.issued.map((i: any) => i.code)) ===
  JSON.stringify(outcome.issued.map((i: any) => i.code));
console.log(
  `   codes returned: ${replayOutcome.issued.map((i: any) => i.code).join(', ')} ` +
    `(replayed=${replayOutcome.issued.every((i: any) => i.replayed)})`,
);
console.log(sameCodes ? '   OK: same codes, nothing duplicated.' : '   PROBLEM: duplicates minted.');

heading('8.', 'The internet drops mid-shift — can the counter still sell?');
await api('POST', '/api/v1/sim/offline', { offline: true });
const offlineSale = await api('POST', '/api/v1/vouchers', {
  siteId: SITE,
  planId: '1h',
  reference: 'R-1043',
});
console.log(
  `   HTTP ${offlineSale.status}  code ${offlineSale.body.code}  source=${offlineSale.body.source}`,
);
for (const n of offlineSale.body.notes ?? []) console.log(`   note: ${n}`);
await api('POST', '/api/v1/sim/offline', { offline: false });
console.log('   (link restored; reserve refills on the next sale)');

heading('9.', 'Where the site stands');
await showStatus('   ');
console.log(`\n   ledger rows: ${ledger.issuances(SITE).length}`);
console.log('   slips written to data/print-log.txt\n');

server.close();
process.exit(0);
