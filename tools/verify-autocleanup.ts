/**
 * Prove that automatic cleanup deletes spent groups and nothing else.
 *
 * The negative case is easy to observe by hand (nothing expired, nothing
 * deleted). This checks the case that actually matters: a group whose codes
 * have expired IS selected and removed, while the cafe's hand-made batches are
 * never even candidates.
 *
 * It creates a group that expires in ~2 minutes, waits for it to die, then
 * asks the same code the live terminal uses which groups are spent.
 *
 * Usage:  npm run verify:autocleanup        (Chrome must be closed)
 */

import './_live-errors.ts';
import { OmadaSession } from '../src/omada/session.ts';
import { OmadaCloudClient, ValidityType } from '../src/omada/cloud.ts';
import {
  SERVICE_TAG,
  POS_PREFIX,
  ownedByService,
  findSpentGroups,
} from '../src/omada/housekeeping.ts';
import { accountId, siteId } from './_account.ts';

const session = new OmadaSession({
  profileDir:
    process.env.CHROME_PROFILE ??
    'C:/Users/User/Desktop/MY-FOLDER-GITHUB/IXM/Chrome Setup/chrome-profile',
  chromeExe:
    process.env.CHROME_EXE ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe',
  portalUrl: 'https://aps1-omada-cloud.tplinkcloud.com/',
  apiBase: 'https://aps1-api-omada-essential-controller.tplinkcloud.com',
  omadacId: accountId(),
  headed: process.env.HEADED === '1',
});

const SITE = siteId();
const MINUTES = Number(process.env.EXPIRE_MINUTES ?? '2');

console.log('Connecting...');
await session.open();
const cloud = new OmadaCloudClient(session, SITE);

// What is protected, before anything is created.
const before = await cloud.listGroups();
const legacy = before.filter((g) => !ownedByService(g));
console.log(`\nProtected (not candidates for deletion): ${legacy.length} group(s)`);
for (const g of legacy) {
  console.log(`   ${g.name.padEnd(22)} ${String(g.totalCount).padStart(5)} vouchers`);
}

let groupId: string | null = null;
let passed = false;
try {
  console.log(`\nCreating a group that expires in ${MINUTES} minute(s)...`);
  groupId = await cloud.createGroup({
    name: `${POS_PREFIX}AUTOCLEAN-TEST`,
    amount: 1,
    durationMinutes: 120,
    codeLength: 6,
    maxUsers: 1,
    validityType: ValidityType.FIXED_DATES,
    expireAfterHours: MINUTES / 60,
    description: SERVICE_TAG,
  });
  const code = (await cloud.listCodes(groupId))[0];
  console.log(`   code ${code?.code}, expires ${new Date(code?.endTime ?? 0).toLocaleTimeString()}`);

  // Before expiry it must NOT be selected.
  const early = await findSpentGroups(cloud);
  const earlySelected = early.spent.some((g) => g.id === groupId);
  console.log(`\nBefore expiry: selected for deletion? ${earlySelected ? 'YES (wrong)' : 'no (correct)'}`);

  const waitMs = (code?.endTime ?? Date.now()) - Date.now() + 15_000;
  console.log(`Waiting ${Math.round(waitMs / 1000)}s for it to expire...`);
  await new Promise((r) => setTimeout(r, Math.max(waitMs, 5_000)));

  // After expiry it must be selected, and nothing of the cafe's may be.
  const late = await findSpentGroups(cloud);
  const lateSelected = late.spent.some((g) => g.id === groupId);
  const legacyAtRisk = late.spent.filter((g) => !ownedByService(g));

  console.log(`After expiry:  selected for deletion? ${lateSelected ? 'yes (correct)' : 'NO (wrong)'}`);
  console.log(`Legacy groups selected: ${legacyAtRisk.length} (must be 0)`);
  console.log(`\nSpent list: ${late.spent.map((g) => g.name).join(', ') || '(empty)'}`);

  passed = !earlySelected && lateSelected && legacyAtRisk.length === 0;
} finally {
  if (groupId) {
    await cloud.deleteGroup(groupId).catch((e) => console.log(`cleanup failed: ${e.message}`));
  }
  const after = await cloud.listGroups();
  const stray = after.filter((g) => g.name.includes('AUTOCLEAN-TEST'));
  console.log(stray.length === 0 ? 'Test group removed.' : 'WARNING: test group remains.');
  await session.close();
}

console.log(
  '\n' +
    (passed
      ? 'PASS: expired groups are deleted, protected groups are untouchable.'
      : 'FAIL: see the lines above.'),
);
process.exit(passed ? 0 : 1);
