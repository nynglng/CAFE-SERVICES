/**
 * End-to-end proof against the real Omada Cloud site.
 *
 * Creates a one-voucher group that EXPIRES, reads its code back, confirms the
 * expiry is a real date rather than "never", then deletes it. This is the
 * whole loop the service depends on, exercised for real.
 *
 * Every group is named ZZ-VERIFY-* and deleted in a finally block.
 *
 * Usage:  npm run verify:live        (Chrome must be closed)
 */

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
  headed: true,
});

const SITE = '<omada-site-id>';

console.log('Opening session...');
await session.open();
const cloud = new OmadaCloudClient(session, SITE);

console.log('\n1. Current state of the site');
const before = await cloud.listGroups();
const totalBefore = before.reduce((s, g) => s + g.totalCount, 0);
console.log(`   ${before.length} groups, ${totalBefore} vouchers stored (cap 5000)`);
for (const g of before) {
  console.log(
    `     ${g.name.padEnd(12)} ${String(g.totalCount).padStart(5)} total  ` +
      `${String(g.unusedCount).padStart(5)} unused  ${g.usedCount} used  ` +
      `validityType=${g.validityType}`,
  );
}

let groupId: string | null = null;
try {
  console.log('\n2. Creating a 1-voucher group that EXPIRES tomorrow');
  groupId = await cloud.createGroup({
    name: `ZZ-VERIFY-${Date.now()}`,
    amount: 1,
    durationMinutes: 120,
    codeLength: 6,
    maxUsers: 1,
    validityType: ValidityType.FIXED_DATES,
    expireAfterDays: 1,
  });
  console.log(`   group id: ${groupId}`);

  console.log('\n3. Reading the code back');
  const codes = await cloud.listCodes(groupId);
  const v = codes[0];
  if (!v) throw new Error('no codes returned');
  console.log(`   code       : ${v.code}`);
  console.log(`   status     : ${v.status} (0=unused)`);
  console.log(`   session len: ${v.timeLeftSec / 60} minutes once redeemed`);
  console.log(
    `   expires    : ${v.endTime === null ? 'NEVER (wrong!)' : new Date(v.endTime).toLocaleString()}`,
  );

  console.log(
    v.endTime === null
      ? '\n   RESULT: FAILED — voucher still never expires.'
      : '\n   RESULT: SUCCESS — voucher will self-destruct, no manual cleanup needed.',
  );
} finally {
  if (groupId) {
    console.log('\n4. Deleting the test group');
    await cloud
      .deleteGroup(groupId)
      .then(() => console.log('   deleted'))
      .catch((e) => console.log(`   !! cleanup failed: ${e.message}`));
  }
}

const after = await cloud.listGroups();
const leftovers = after.filter((g) => g.name.startsWith('ZZ-VERIFY-'));
console.log(
  leftovers.length === 0
    ? '\nCleanup verified: no test groups remain.'
    : `\nWARNING: ${leftovers.length} test groups still present.`,
);

await session.close();
process.exit(0);
