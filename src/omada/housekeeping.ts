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

/**
 * Groups of ours where no code can still be redeemed.
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
    const stillUsable = codes.some(
      (c) => c.status === 0 && (c.endTime === null || c.endTime > now),
    );
    if (!stillUsable) spent.push(g);
  }
  return { candidates, spent };
}
