package com.cafeservices.printgateway;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides what the printer should actually produce for a sale.
 *
 * Three cases, and the WiFi item is what separates them:
 *
 *   nothing but WiFi        -> voucher only; a receipt listing one line the
 *                              customer did not ask for is just wasted paper
 *   WiFi alongside items    -> receipt then voucher
 *   no WiFi                 -> receipt only, passed through untouched
 *
 * In both receipt cases the WiFi line is taken out of the item list: it has
 * been replaced by the voucher below it, so printing it twice would only
 * confuse whoever reads the slip.
 */
public final class SalePlan {

    /** Item names that mean "this sale includes WiFi access". */
    private static final String[] WIFI_MARKERS = {"wi-fi", "wifi"};

    public final boolean printReceipt;
    public final boolean printVoucher;
    public final int voucherCount;
    /** Receipt rows with the WiFi lines removed. */
    public final List<String> receiptRows;
    public final String receiptNumber;
    public final String wifiItemName;

    private SalePlan(boolean printReceipt, boolean printVoucher, int voucherCount,
                     List<String> receiptRows, String receiptNumber, String wifiItemName) {
        this.printReceipt = printReceipt;
        this.printVoucher = printVoucher;
        this.voucherCount = voucherCount;
        this.receiptRows = receiptRows;
        this.receiptNumber = receiptNumber;
        this.wifiItemName = wifiItemName;
    }

    public static SalePlan from(ReceiptParser.Receipt receipt) {
        ReceiptParser.Item wifi = null;
        int others = 0;
        for (ReceiptParser.Item item : receipt.items) {
            if (isWifi(item.name)) {
                wifi = item;
            } else {
                others++;
            }
        }

        if (wifi == null) {
            return new SalePlan(true, false, 0,
                    new ArrayList<String>(receipt.rows), receipt.receiptNumber, "");
        }

        int count = Math.max(1, wifi.wholeQuantity());
        boolean receiptWanted = others > 0;
        List<String> rows = receiptWanted
                ? withoutItem(receipt.rows, wifi.name)
                : new ArrayList<String>();

        return new SalePlan(receiptWanted, true, count, rows,
                receipt.receiptNumber, wifi.name);
    }

    public static boolean isWifi(String name) {
        String lower = name.toLowerCase();
        for (String marker : WIFI_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Drop an item from the printed rows.
     *
     * An item occupies two rows - the name and amount, then the quantity
     * beneath it - so both go, along with a blank row left behind, which would
     * otherwise show up as a gap where the line used to be.
     */
    private static List<String> withoutItem(List<String> rows, String itemName) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < rows.size(); i++) {
            String row = rows.get(i);
            String trimmed = row.trim();

            boolean isTheItem = trimmed.length() > 0
                    && trimmed.toLowerCase().startsWith(itemName.toLowerCase());
            if (!isTheItem) {
                out.add(row);
                continue;
            }

            // Skip the quantity row that belongs to it.
            if (i + 1 < rows.size() && rows.get(i + 1).trim().matches("^\\d+(\\.\\d+)?\\s*x\\s.*")) {
                i++;
            }
            // And a blank row that was separating it from the next item.
            if (i + 1 < rows.size() && rows.get(i + 1).trim().length() == 0) {
                i++;
            }
        }
        return out;
    }

    public String describe() {
        if (printReceipt && printVoucher) {
            return "receipt (WiFi line removed) + " + voucherCount + " voucher(s)";
        }
        if (printVoucher) {
            return voucherCount + " voucher(s) only, no receipt";
        }
        return "receipt only, passed through";
    }
}
