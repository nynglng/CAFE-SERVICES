import { planForSku, sites } from '../config.ts';
import { issue, IssueFailedError } from '../domain/issuer.ts';
import { printSlip } from '../print/printer.ts';
import type { IssueResult } from '../domain/issuer.ts';

/**
 * Loyverse fires `receipts.update` when a sale is completed. We look for line
 * items that map to a WiFi plan, mint one voucher per unit sold, and print a
 * slip. Everything else on the receipt is ignored.
 *
 * Loyverse retries webhooks it thinks failed, so every voucher is keyed to
 * `receipt:lineItem:index` — a replayed delivery returns the same codes instead
 * of minting new ones.
 *
 * NOTE: the exact payload keys below follow the documented Loyverse receipts
 * webhook. Point a test sale at this endpoint and check the logged payload
 * before going live; only this file needs to change if a key differs.
 */

type LoyverseLineItem = {
  id?: string;
  line_item_id?: string;
  sku?: string;
  item_name?: string;
  variant_name?: string;
  quantity?: number;
};

type LoyverseReceipt = {
  receipt_number?: string;
  store_id?: string;
  receipt_date?: string;
  line_items?: LoyverseLineItem[];
};

export type WebhookPayload = {
  type?: string;
  merchant_id?: string;
  receipts?: LoyverseReceipt[];
};

export type WebhookOutcome = {
  receiptNumber: string;
  siteId: string;
  issued: Array<{
    planId: string;
    code: string;
    issuanceId: string;
    source: string;
    replayed: boolean;
    print: { printed: boolean; target: string };
  }>;
  skipped: Array<{ item: string; reason: string }>;
  errors: Array<{ item: string; message: string }>;
};

/** Which of our sites this receipt belongs to. */
function resolveSiteId(receipt: LoyverseReceipt, override: string | null): string | null {
  if (override) return override;
  if (receipt.store_id) {
    const bySite = sites.find((s) => s.loyverseStoreId === receipt.store_id);
    if (bySite) return bySite.id;
  }
  // A single-client deployment needs no mapping at all.
  return sites.length === 1 ? sites[0]!.id : null;
}

export async function handleReceipts(
  payload: WebhookPayload,
  siteOverride: string | null,
): Promise<WebhookOutcome[]> {
  const outcomes: WebhookOutcome[] = [];

  for (const receipt of payload.receipts ?? []) {
    const receiptNumber = receipt.receipt_number ?? 'unknown';
    const siteId = resolveSiteId(receipt, siteOverride);
    const outcome: WebhookOutcome = {
      receiptNumber,
      siteId: siteId ?? 'unresolved',
      issued: [],
      skipped: [],
      errors: [],
    };

    if (!siteId) {
      outcome.errors.push({
        item: receiptNumber,
        message: 'Could not map this receipt to a site (set loyverseStoreId or pass ?siteId=)',
      });
      outcomes.push(outcome);
      continue;
    }

    for (const line of receipt.line_items ?? []) {
      const label = line.sku ?? line.item_name ?? 'unnamed item';
      const plan = planForSku(siteId, line.sku ?? line.item_name ?? '');
      if (!plan) {
        outcome.skipped.push({ item: label, reason: 'not a WiFi plan' });
        continue;
      }

      const quantity = Math.max(1, Math.round(line.quantity ?? 1));
      const lineId = line.id ?? line.line_item_id ?? label;

      for (let n = 0; n < quantity; n++) {
        try {
          const result: IssueResult = await issue({
            siteId,
            planId: plan.id,
            reference: receiptNumber,
            idempotencyKey: `${receiptNumber}:${lineId}:${n}`,
          });
          const print = await printSlip(result.slip);
          outcome.issued.push({
            planId: plan.id,
            code: result.voucher.code,
            issuanceId: result.issuance.issuanceId,
            source: result.source,
            replayed: result.replayed,
            print: { printed: print.printed, target: print.target },
          });
        } catch (err) {
          const message =
            err instanceof IssueFailedError ? err.message : (err as Error).message;
          outcome.errors.push({ item: label, message });
        }
      }
    }

    outcomes.push(outcome);
  }

  return outcomes;
}
