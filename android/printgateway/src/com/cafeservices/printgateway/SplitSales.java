package com.cafeservices.printgateway;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stops a split bill from buying the same vouchers twice.
 *
 * When a bill is split, Loyverse prints one receipt per payment and EVERY one
 * carries the whole item list. Left alone, a sale of three vouchers paid half
 * cash and half card issues three codes, then another three - verified on the
 * counter, six codes for three sold.
 *
 * The two halves cannot be told apart by what they say. Both show the same
 * items, the same total, the same time, no receipt number, and both carry an
 * "Amount due" row; only the payment line differs. So there is no "final" half
 * to hold the vouchers back for, and the fix is to recognise the second print
 * as the same sale and hand it the codes already issued.
 *
 * Reusing rather than skipping is deliberate: whichever slip the guest walks
 * away with has working codes on it, and they are the same codes, so nothing
 * is spent twice.
 *
 * Only split payments are eligible, which is what keeps this from ever
 * merging two genuine sales: an ordinary receipt has no "Amount due" row at
 * all, so two customers buying an identical basket can never collide here.
 */
public final class SplitSales {

    /** How long a half waits for its partner. Splits print seconds apart. */
    private static final long WINDOW_MS = 10 * 60 * 1000L;

    private static final Map<String, Entry> RECENT = new LinkedHashMap<String, Entry>();

    private static final class Entry {
        final List<String> codes;
        final long issuedAt;

        Entry(List<String> codes, long issuedAt) {
            this.codes = codes;
            this.issuedAt = issuedAt;
        }
    }

    private SplitSales() {
    }

    /**
     * What identifies this sale across its halves, or null when the receipt is
     * not part of a split.
     *
     * Built from the items and the total - never the payment rows, which are
     * the one thing that differs between halves.
     */
    public static String fingerprint(ReceiptParser.Receipt receipt) {
        boolean split = false;
        String total = "";
        for (String row : receipt.rows) {
            String t = row.trim();
            if (t.startsWith("Amount due")) {
                split = true;
            } else if (t.startsWith("Total")) {
                total = t;
            }
        }
        if (!split) {
            return null;
        }

        StringBuilder sb = new StringBuilder(total);
        for (ReceiptParser.Item item : receipt.items) {
            sb.append('|').append(item.name).append('x')
                    .append(item.wholeQuantity()).append('@').append(item.amount);
        }
        return sb.toString();
    }

    /** Codes already issued for this sale, or null if it is the first half. */
    public static synchronized List<String> codesFor(String fingerprint) {
        prune();
        Entry entry = RECENT.get(fingerprint);
        return entry == null ? null : new ArrayList<String>(entry.codes);
    }

    public static synchronized void remember(String fingerprint, List<String> codes) {
        prune();
        RECENT.put(fingerprint, new Entry(new ArrayList<String>(codes),
                System.currentTimeMillis()));
    }

    private static void prune() {
        long cutoff = System.currentTimeMillis() - WINDOW_MS;
        java.util.Iterator<Map.Entry<String, Entry>> it = RECENT.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().issuedAt < cutoff) {
                it.remove();
            }
        }
    }
}
