/**
 * Does an expired voucher actually LOOK expired to us?
 *
 * The cleanup in tools/cli-live.ts deletes a group once none of its codes are
 * still redeemable, which it decides from `status === 0`. That is only correct
 * if Omada flips the status when the expiration date passes. If expired codes
 * kept status 0 forever, cleanup would never fire and the whole pile-up problem
 * would still be there, just hidden.
 *
 * This creates a voucher that expires in ~2 minutes, watches it, and reports
 * what actually changes. The group is deleted in a finally block.
 *
 * Usage:  npm run verify:expiry        (Chrome must be closed)
 */

import './_live-errors.ts';
import { OmadaSession } from '../src/omada/session.ts';
import { OmadaCloudClient, ValidityType } from '../src/omada/cloud.ts';

const session = new OmadaSession({
  profileDir:
    process.env.CHROME_PROFILE ??
    'C:/Users/USER/Desktop/MY-FOLDER-GITHUB/IXM/Chrome Setup/chrome-profile',
  chromeExe:
    process.env.CHROME_EXE ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe',
  portalUrl: 'https://aps1-omada-cloud.tplinkcloud.com/',
  apiBase: 'https://aps1-api-omada-essential-controller.tplinkcloud.com',
  omadacId: '<omada-account-id>',
  headed: process.env.HEADED === '1',
});

const SITE = process.env.OMADA_SITE_ID ?? '<omada-site-id>';
const MINUTES = Number(process.env.EXPIRE_MINUTES ?? '2');

const statusName = (s: number): string =>
  s === 0 ? 'unused' : s === 1 ? 'in use' : s === 2 ? 'EXPIRED' : `unknown(${s})`;

console.log('Connecting...');
await session.open();
const cloud = new OmadaCloudClient(session, SITE);

const groupsBefore = await cloud.listGroups();
const totalBefore = groupsBefore.reduce((s, g) => s + g.totalCount, 0);
console.log(`Site currently stores ${totalBefore} vouchers.\n`);

let groupId: string | null = null;
try {
  const name = `ZZ-EXPTEST-${Date.now()}`;
  console.log(`Creating 1 voucher that expires in ${MINUTES} minute(s)...`);
  groupId = await cloud.createGroup({
    name,
    amount: 1,
    durationMinutes: 120,
    codeLength: 6,
    maxUsers: 1,
    validityType: ValidityType.FIXED_DATES,
    expireAfterHours: MINUTES / 60,
  });

  const first = (await cloud.listCodes(groupId))[0];
  if (!first) throw new Error('no code returned');
  const expiresAt = first.endTime;
  console.log(`  code ${first.code}  status=${statusName(first.status)}`);
  console.log(`  expires ${expiresAt === null ? 'never' : new Date(expiresAt).toLocaleString()}\n`);

  const deadline = (expiresAt ?? Date.now()) + 90_000;
  let flipped = false;

  while (Date.now() < deadline) {
    await new Promise((r) => setTimeout(r, 20_000));
    const codes = await cloud.listCodes(groupId);
    const v = codes[0];
    const group = (await cloud.listGroups()).find((g) => g.id === groupId);
    const past = expiresAt !== null && Date.now() > expiresAt;

    console.log(
      `  t=${new Date().toLocaleTimeString()}  ` +
        `${past ? 'PAST expiry' : 'before expiry'}  ` +
        `codes=${codes.length}  ` +
        `status=${v ? statusName(v.status) : '(row gone)'}  ` +
        `group.total=${group?.totalCount ?? '(group gone)'}  ` +
        `unused=${group?.unusedCount ?? '-'}`,
    );

    if (past && (!v || v.status !== 0 || codes.length === 0)) {
      flipped = true;
      break;
    }
  }

  console.log('\n' + '─'.repeat(70));
  if (flipped) {
    console.log('RESULT: expiry is visible to us — cleanup can detect and delete it.');
  } else {
    console.log('RESULT: the code still reads as unused after its expiry date.');
    console.log('        cleanup keyed on status===0 would NEVER delete it.');
    console.log('        Cleanup must use the expiration date, not the status.');
  }
} finally {
  if (groupId) {
    await cloud
      .deleteGroup(groupId)
      .then(() => console.log('\nTest group deleted.'))
      .catch((e) => console.log(`\n!! cleanup failed: ${e.message}`));
  }
  await session.close();
}
process.exit(0);
