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
 * Housekeeping happens AFTER the codes are in hand, in its own try, so a
 * problem clearing old groups cannot cost a customer their voucher.
 */
public final class VoucherIssuer {

    private VoucherIssuer() {
    }

    /** One attempt at the codes. False means Omada would not answer. */
    private static boolean issue(PrintJob job, SalePlan plan) {
        try {
            List<String> codes = OmadaClient.issue(plan.voucherCount,
                    Settings.accessHours(), Settings.expiryHours());
            job.setIssuedCodes(codes);
            Log.i(PrintServerService.TAG, "issued " + codes.size() + " voucher(s)");
            return true;
        } catch (Exception e) {
            Log.e(PrintServerService.TAG, "could not issue vouchers: " + e);
            return false;
        }
    }

    public static void fill(PrintJob job, android.content.Context context) {
        ReceiptParser.Receipt receipt = ReceiptParser.parse(job.decoded);
        if (receipt.items.isEmpty()) {
            return;
        }
        SalePlan plan = SalePlan.from(receipt);
        if (!plan.printVoucher) {
            return;
        }

        if (!issue(job, plan)) {
            // A lapsed controller session is the ordinary reason, and it is
            // fixed by loading the portal again rather than by a person. One
            // retry: if the account login itself has gone, no amount of
            // reloading will help and the queue should not be kept waiting.
            if (!OmadaReconnector.revive(context) || !issue(job, plan)) {
                return;
            }
        }

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
    }
}
