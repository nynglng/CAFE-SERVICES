/**
 * Find the endpoint that returns the actual voucher CODES inside a group.
 *
 * Creating a group returns only `{ id }` — the codes have to be read back
 * separately, and that call was not captured. This tries the plausible shapes
 * against an EXISTING group, so it is strictly read-only: nothing is created,
 * modified, or deleted on the live site.
 *
 * Usage:  npm run discover        (Chrome must be closed)
 */

import './_live-errors.ts';
import { OmadaSession } from '../src/omada/session.ts';

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
const HOTSPOT = `/api/v2/hotspot/sites/${SITE}`;

console.log('Opening session...');
await session.open();

// Pick a real group to read from.
const groups = (await session.get(
  `${HOTSPOT}/voucherGroups?currentPage=1&currentPageSize=10&searchField=name%2Ccode`,
)) as { data: Array<{ id: string; name: string; totalCount: number }> };

console.log('\nExisting groups:');
for (const g of groups.data) {
  console.log(`  ${g.id}  ${String(g.totalCount).padStart(5)} vouchers  ${g.name}`);
}

const target = groups.data.find((g) => g.totalCount > 0);
if (!target) {
  console.log('\nNo non-empty group to read from.');
  await session.close();
  process.exit(0);
}
console.log(`\nReading codes from group "${target.name}" (${target.id})\n${'─'.repeat(78)}`);

const candidates = [
  `${HOTSPOT}/voucherGroups/${target.id}`,
  `${HOTSPOT}/voucherGroups/${target.id}?currentPage=1&currentPageSize=3`,
  `${HOTSPOT}/voucherGroups/${target.id}/vouchers?currentPage=1&currentPageSize=3`,
  `${HOTSPOT}/vouchers?groupId=${target.id}&currentPage=1&currentPageSize=3`,
  `${HOTSPOT}/voucherGroups/${target.id}/voucher?currentPage=1&currentPageSize=3`,
  `${HOTSPOT}/setting/voucher/${target.id}?currentPage=1&currentPageSize=3`,
];

for (const path of candidates) {
  try {
    const result = await session.get(path);
    const preview = JSON.stringify(result);
    console.log(`OK   ${path.replace(HOTSPOT, '')}`);
    console.log(`     ${preview.slice(0, 400)}\n`);
  } catch (err) {
    console.log(`FAIL ${path.replace(HOTSPOT, '')}  -> ${(err as Error).message}`);
  }
}

await session.close();
process.exit(0);
