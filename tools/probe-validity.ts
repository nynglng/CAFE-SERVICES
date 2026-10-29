/**
 * Work out which fields make Omada vouchers EXPIRE instead of living forever.
 *
 * The live site's groups all use validityType 0 (Permanent), which is why
 * unused codes accumulate until they hit the 5000 cap. The portal's create form
 * has a "Voucher Validity" dropdown, but the field it sends was not captured
 * (the manual flow always left it on Permanent).
 *
 * This creates a ONE-voucher group per candidate, reads its endTime back, and
 * deletes the group immediately. A finite endTime means that candidate works.
 *
 * Safety: every group is named ZZ-APITEST-* and deleted in a finally block, so
 * nothing is left behind on the live site even if a candidate errors.
 *
 * Usage:  npm run probe:validity        (Chrome must be closed)
 */

import './_live-errors.ts';
import { OmadaSession } from '../src/omada/session.ts';
import { accountId, siteId } from './_account.ts';

const session = new OmadaSession({
  profileDir:
    process.env.CHROME_PROFILE ??
    'C:/Users/USER/Desktop/MY-FOLDER-GITHUB/IXM/Chrome Setup/chrome-profile',
  chromeExe:
    process.env.CHROME_EXE ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe',
  portalUrl: 'https://aps1-omada-cloud.tplinkcloud.com/',
  apiBase: 'https://aps1-api-omada-essential-controller.tplinkcloud.com',
  omadacId: accountId(),
  headed: true,
});

const SITE = siteId();
const HOTSPOT = `/api/v2/hotspot/sites/${SITE}`;

/** The portal's baseline create body, with validity left at Permanent. */
function baseBody(name: string): Record<string, unknown> {
  return {
    codeLength: 6,
    codeForm: [0],
    amount: 1,
    name,
    type: 2,
    logout: true,
    downLimitUnit: 0,
    upLimitUnit: 0,
    trafficLimitEnable: false,
    trafficLimit: null,
    trafficLimitUnit: 0,
    voucherValidityEnable: false,
    upTimeLimitEnable: false,
    durationType: 1,
    duration: 120,
    description: null,
    maxUsers: 1,
    trafficLimitFrequency: 1,
    applyToAllPortals: true,
    validityType: 0,
    startTime: '00:00',
    endTime: '23:59',
    scheduleTime: 0,
    weeklyEnableDays: { 1: true, 2: true, 3: true, 4: true, 5: true, 6: true, 7: true },
    pattern: {
      patternType: 2,
      position: 0,
      ssidNetworkEnable: false,
      durationEnable: false,
      limitEnable: false,
    },
  };
}

function neverExpires(endTime: unknown): boolean {
  return String(endTime).startsWith('922337203685477');
}

type Candidate = { label: string; patch: Record<string, unknown> };

const candidates: Candidate[] = [
  {
    label: 'baseline (Permanent, as used today)',
    patch: {},
  },
  {
    label: 'validityType=1 + voucherValidity=24',
    patch: { voucherValidityEnable: true, validityType: 1, voucherValidity: 24 },
  },
  {
    label: 'validityType=1 + several field aliases=24',
    patch: {
      voucherValidityEnable: true,
      validityType: 1,
      voucherValidity: 24,
      validityTime: 24,
      expireTime: 24,
      effectiveTime: 24,
      validity: 24,
    },
  },
  {
    label: 'validityType=2 + several field aliases=24',
    patch: {
      voucherValidityEnable: true,
      validityType: 2,
      voucherValidity: 24,
      validityTime: 24,
      expireTime: 24,
      effectiveTime: 24,
      validity: 24,
    },
  },
];

console.log('Opening session...');
await session.open();
console.log(`\n${'─'.repeat(80)}`);

for (const [i, candidate] of candidates.entries()) {
  const name = `ZZ-APITEST-${Date.now()}-${i}`;
  let groupId: string | null = null;
  try {
    const body = { ...baseBody(name), ...candidate.patch };
    const created = (await session.post(`${HOTSPOT}/voucherGroups`, body)) as { id?: string };
    groupId = created?.id ?? null;
    if (!groupId) {
      console.log(`${candidate.label}\n  -> no group id returned\n`);
      continue;
    }

    const detail = (await session.get(
      `${HOTSPOT}/voucherGroups/${groupId}?currentPage=1&currentPageSize=1`,
    )) as { data?: Array<{ code?: string; endTime?: unknown }>; validityType?: number };

    const row = detail.data?.[0];
    const expires = !neverExpires(row?.endTime);
    console.log(candidate.label);
    console.log(`  code=${row?.code}  endTime=${row?.endTime}`);
    console.log(
      `  -> ${expires ? 'EXPIRES on ' + new Date(Number(row?.endTime)).toISOString() : 'never expires'}\n`,
    );
  } catch (err) {
    console.log(`${candidate.label}\n  -> rejected: ${(err as Error).message}\n`);
  } finally {
    if (groupId) {
      await session
        .del(`${HOTSPOT}/voucherGroups/${groupId}`)
        .catch((e) => console.log(`  !! cleanup failed for ${groupId}: ${e.message}`));
    }
  }
}

// Prove nothing was left behind.
const groups = (await session.get(
  `${HOTSPOT}/voucherGroups?currentPage=1&currentPageSize=100&searchField=name%2Ccode`,
)) as { data?: Array<{ name: string; id: string }> };
const leftovers = (groups.data ?? []).filter((g) => g.name.startsWith('ZZ-APITEST-'));
console.log(
  leftovers.length === 0
    ? 'Cleanup verified: no ZZ-APITEST groups remain.'
    : `WARNING: ${leftovers.length} test groups still present: ${leftovers.map((g) => g.id).join(', ')}`,
);

await session.close();
process.exit(0);
