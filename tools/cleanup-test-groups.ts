/**
 * Inspect and remove any leftover test groups from the live site.
 *
 * The verification tools name their groups ZZ-EXPTEST-*, ZZ-VERIFY-* and
 * ZZ-APITEST-* and normally delete them in a finally block. If a run is
 * interrupted (the browser closing, for instance) one can survive. This finds
 * them, reports what state their codes are in, and deletes them.
 *
 * Reporting the state first is deliberate: a leftover that is already past its
 * expiry answers the question of whether Omada actually marks expired vouchers,
 * which is what the cleanup in cli-live.ts depends on.
 *
 * Only ZZ-* groups are ever touched. The cafe's real voucher batches are not
 * matched by any of these prefixes.
 *
 * Usage:  npm run cleanup:tests
 */

import './_live-errors.ts';
import { OmadaSession } from '../src/omada/session.ts';
import { OmadaCloudClient } from '../src/omada/cloud.ts';

const TEST_PREFIXES = ['ZZ-EXPTEST-', 'ZZ-VERIFY-', 'ZZ-APITEST-'];

const session = new OmadaSession({
  profileDir:
    process.env.CHROME_PROFILE ??
    'C:/Users/User/Desktop/MY-FOLDER-GITHUB/IXM/Chrome Setup/chrome-profile',
  chromeExe:
    process.env.CHROME_EXE ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe',
  portalUrl: 'https://aps1-omada-cloud.tplinkcloud.com/',
  apiBase: 'https://aps1-api-omada-essential-controller.tplinkcloud.com',
  omadacId: '<omada-account-id>',
  headed: process.env.HEADED === '1',
});

const SITE = process.env.OMADA_SITE_ID ?? '<omada-site-id>';
const statusName = (s: number): string =>
  s === 0 ? 'unused' : s === 1 ? 'in use' : s === 2 ? 'EXPIRED' : `unknown(${s})`;

console.log('Connecting...');
await session.open();
const cloud = new OmadaCloudClient(session, SITE);

const groups = await cloud.listGroups();
const strays = groups.filter((g) => TEST_PREFIXES.some((p) => g.name.startsWith(p)));

console.log(`\nSite holds ${groups.reduce((s, g) => s + g.totalCount, 0)} vouchers total.`);

if (strays.length === 0) {
  console.log('No leftover test groups. Nothing to do.');
} else {
  console.log(`Found ${strays.length} leftover test group(s):\n`);
  for (const g of strays) {
    const codes = await cloud.listCodes(g.id);
    for (const c of codes) {
      const past = c.endTime !== null && Date.now() > c.endTime;
      console.log(`  ${g.name}`);
      console.log(`    code ${c.code}  status=${statusName(c.status)}`);
      console.log(
        `    expiry ${c.endTime === null ? 'never' : new Date(c.endTime).toLocaleString()}` +
          ` (${past ? 'PAST' : 'not yet reached'})`,
      );
      if (past) {
        console.log(
          c.status === 0
            ? '    >> still reads as UNUSED past its expiry: status alone is NOT' +
                '\n       a reliable signal, cleanup must compare the expiry date.'
            : '    >> status changed after expiry: cleanup can rely on it.',
        );
      }
    }
    await cloud.deleteGroup(g.id);
    console.log('    deleted\n');
  }
}

const after = await cloud.listGroups();
const remaining = after.filter((g) => TEST_PREFIXES.some((p) => g.name.startsWith(p)));
console.log(
  remaining.length === 0
    ? 'Verified: no test groups remain.'
    : `WARNING: ${remaining.length} still present.`,
);

await session.close();
process.exit(0);
