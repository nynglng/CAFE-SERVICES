import type { OmadaCloudClient, CloudGroup } from './cloud.ts';
import { ValidityType } from './cloud.ts';

/**
 * Deciding which voucher groups automation is allowed to delete.
 *
 * This lives apart from the tools on purpose: the rule that protects the
 * cafe's hand-made voucher batches is the single most destructive thing in the
 * codebase to get wrong, so the tool that deletes and the test that checks the
 * deleting both use exactly this code, and cannot drift apart.
 */

/** Written into the description of every group this service creates. */
export const SERVICE_TAG = 'Issued by cafe-voucher-service';

/** Name prefix for groups this service creates. */
export const POS_PREFIX = 'POS-';

/**
 * Whether automation may touch a group.
 *
 * All three markers must hold. The description is the real guard — groups made
 * by hand in the Omada portal have no description at all — while the name and
 * validity are belt-and-braces, so a group would have to look deliberately
 * like ours in three separate ways before it were ever at risk.
 */
export function ownedByService(g: CloudGroup): boolean {
  return (
    g.description === SERVICE_TAG &&
    g.name.startsWith(POS_PREFIX) &&
    g.validityType === ValidityType.FIXED_DATES
  );
}

/** What Omada means by a voucher's status. */
export const STATUS_UNUSED = 0;
export const STATUS_IN_USE = 1;
export const STATUS_EXPIRED = 2;

/**
 * Whether a code is still worth something to somebody.
 *
 * Only the unused case was checked here at first, and that cut paying
 * customers off the wifi: once every code in a group had been redeemed the
 * group looked spent, so the next sale deleted it out from under them.
 *
 * An in-use code is kept whatever its expiry says. That timestamp bounds when
 * a code may be redeemed, not how long the access it bought lasts - redeem one
 * at ten to midnight and the two hours run past it - so reading a passed
 * expiry as "spent" would be the same mistake in a narrower window. Omada
 * moves a finished code to EXPIRED on its own, and that is the signal to
 * trust.
 */
export function stillWorthKeeping(
  c: { status: number; endTime: number | null },
  now: number,
): boolean {
  if (c.status === STATUS_IN_USE) return true;
  return c.status === STATUS_UNUSED && (c.endTime === null || c.endTime > now);
}

/**
 * Groups of ours where no code is unused or in use.
 *
 * Omada does flip a code to status 2 once its expiry passes (verified against
 * the live site), but the expiry timestamp is checked as well so this stays
 * correct even if that behaviour changes.
 */
export async function findSpentGroups(
  cloud: OmadaCloudClient,
  now: number = Date.now(),
): Promise<{ candidates: CloudGroup[]; spent: CloudGroup[] }> {
  const groups = await cloud.listGroups();
  const candidates = groups.filter(ownedByService);

  const spent: CloudGroup[] = [];
  for (const g of candidates) {
    const codes = await cloud.listCodes(g.id);
    const stillUsable = codes.some((c) => stillWorthKeeping(c, now));
    if (!stillUsable) spent.push(g);
  }
  return { candidates, spent };
}
