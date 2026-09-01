package com.cafeservices.printgateway;

import android.util.Log;

import java.util.List;

/**
 * Gets real voucher codes onto a sale, and tidies up behind it.
 *
 * Runs on the socket thread, before the receipt is drawn, because the codes
 * have to be on the paper that the sale produces - there is no second print.
 *
 * Two rules, both learned the expensive way on the PC tool:
 *
 * A failure here must never lose the sale. If Omada cannot be reached the
 * receipt still prints, with placeholder codes clearly marked as such, and the
 * cashier can fall back to the pre-printed batch the cafe already keeps.
 *
 * Housekeeping happens after the receipt has printed, in its own try, so a
 * problem clearing old groups can cost a customer neither their voucher nor
 * their time. See tidy().
 */
public final class VoucherIssuer {

    private VoucherIssuer() {
    }

    /** One attempt at the codes. False means Omada would not answer. */
    private static boolean issue(PrintJob job, SalePlan plan, String saleId) {
        try {
            List<String> codes = saleId.length() > 0
                    ? OmadaClient.issueFor(saleId, plan.voucherCount,
                            Settings.accessHours(), Settings.expiryHours())
                    : OmadaClient.issue(plan.voucherCount,
                            Settings.accessHours(), Settings.expiryHours());
            job.setIssuedCodes(codes);
            Log.i(PrintServerService.TAG, codes.size() + " voucher code(s) ready");
            return true;
        } catch (Exception e) {
            Log.e(PrintServerService.TAG, "could not issue vouchers: " + e);
            return false;
        }
    }

    public static void fill(PrintJob job, android.content.Context context) {
        long startedAt = System.currentTimeMillis();
        ReceiptParser.Receipt receipt = ReceiptParser.parse(job.decoded);
        if (receipt.items.isEmpty()) {
            return;
        }
        SalePlan plan = SalePlan.from(receipt);
        if (!plan.printVoucher) {
            job.issueMs = System.currentTimeMillis() - startedAt;
            return;
        }

        // The receipt number is the sale's own name for itself, and Omada
        // remembers it in the group name - so a reprint, however much later,
        // gets the codes that sale already bought. Split halves are the one
        // case Loyverse leaves it off, and they fall back to matching on what
        // was sold.
        String saleId = receipt.receiptNumber.replace("#", "");
        String sale = saleId.length() > 0 ? null : SplitSales.fingerprint(receipt);
        if (sale != null) {
            List<String> already = SplitSales.codesFor(sale);
            if (already != null) {
                job.setIssuedCodes(already);
                Log.i(PrintServerService.TAG, "same sale as the last print - "
                        + "reusing " + already.size() + " voucher(s)");
                job.issueMs = System.currentTimeMillis() - startedAt;
                return;
            }
        }

        if (!issue(job, plan, saleId)) {
            // A lapsed controller session is the ordinary reason, and it is
            // fixed by loading the portal again rather than by a person. One
            // retry: if the account login itself has gone, no amount of
            // reloading will help and the queue should not be kept waiting.
            if (!OmadaReconnector.revive(context) || !issue(job, plan, saleId)) {
                job.issueMs = System.currentTimeMillis() - startedAt;
                return;
            }
        }

        if (sale != null && job.hasIssuedCodes()) {
            SplitSales.remember(sale, job.issuedCodes());
        }

        job.issueMs = System.currentTimeMillis() - startedAt;
    }

    /**
     * Clear out spent groups and count the site. Call this AFTER printing.
     *
     * This used to run here, between the codes being in hand and the receipt
     * being drawn, and it was measured at 600-900ms of a sale - larger, by the
     * end, than issuing the codes. None of it is anything the customer needs:
     * it deletes groups in which no code can still be redeemed and writes one
     * line to the log about how full the site is.
     *
     * It also grows. Cleanup asks Omada for the codes of every group we own,
     * one round trip each, and a group lives for as long as its vouchers can
     * be redeemed - so the number of round trips tracks the last day's sales,
     * and the queue got slower exactly as the cafe got busier. Measured at
     * three of our groups it cost 604ms; at four, 922ms.
     *
     * Behind the paper it costs the customer nothing, and it still runs on
     * every sale, so the cap is watched exactly as closely as before.
     */
    public static void tidy(PrintJob job) {
        long startedAt = System.currentTimeMillis();
        try {
            int deleted = OmadaClient.cleanup();
            if (deleted > 0) {
                Log.i(PrintServerService.TAG, "cleanup removed " + deleted + " spent group(s)");
            }
            // The count against the 5000 cap, and how much of it is ours -
            // the whole point of the exercise, and the number that would creep
            // up unnoticed if the ownership rule ever stopped matching.
            Log.i(PrintServerService.TAG, OmadaClient.describeSite());
        } catch (Exception e) {
            Log.e(PrintServerService.TAG, "cleanup failed: " + e);
        }
        job.tidyMs = System.currentTimeMillis() - startedAt;
    }
}
