package com.cafeservices.printgateway;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls the sale out of a Loyverse text-mode print.
 *
 * With print mode set to Text, Loyverse writes fixed 48-column rows and sends
 * no line breaks between them - the printer is expected to wrap. So the stream
 * has to be re-cut into 48-character rows before any of it makes sense.
 *
 * A sold line looks like this, across two rows:
 *
 *     seasalt                                 P170.00
 *     1 x P170.00
 *
 * so the name and total are on one row and the quantity on the next. The
 * receipt number on the footer row gives us something stable to key on, which
 * matters because a voucher must not be minted twice for one sale.
 */
public final class ReceiptParser {

    /** Loyverse's row width in text mode on 80mm paper. */
    public static final int COLUMNS = 48;

    /** "1 x P170.00" - the quantity row that follows an item. */
    private static final Pattern QUANTITY =
            Pattern.compile("^\\s*([0-9]+(?:\\.[0-9]+)?)\\s*x\\s");

    /** "#1-0007" on the footer row. */
    private static final Pattern RECEIPT_NUMBER = Pattern.compile("#\\d+-\\d+");

    public static final class Item {
        public final String name;
        public final double quantity;
        public final String amount;

        Item(String name, double quantity, String amount) {
            this.name = name;
            this.quantity = quantity;
            this.amount = amount;
        }

        public int wholeQuantity() {
            return (int) Math.round(quantity);
        }
    }

    public static final class Receipt {
        public final List<Item> items = new ArrayList<Item>();
        public String receiptNumber = "";
        /** The reflowed rows, useful for showing what was actually parsed. */
        public final List<String> rows = new ArrayList<String>();

        public Item findItem(String needle) {
            String want = needle.toLowerCase();
            for (Item item : items) {
                if (item.name.toLowerCase().contains(want)) {
                    return item;
                }
            }
            return null;
        }
    }

    private ReceiptParser() {
    }

    public static Receipt parse(EscPosDecoder.Result decoded) {
        Receipt receipt = new Receipt();

        for (EscPosDecoder.Line line : decoded.lines) {
            for (String row : reflow(line.text)) {
                receipt.rows.add(row);
            }
        }

        for (int i = 0; i < receipt.rows.size(); i++) {
            String row = receipt.rows.get(i);

            if (receipt.receiptNumber.length() == 0) {
                Matcher m = RECEIPT_NUMBER.matcher(row);
                if (m.find()) {
                    receipt.receiptNumber = m.group();
                }
            }

            // An item is only an item if the row below states a quantity.
            if (i + 1 >= receipt.rows.size()) {
                continue;
            }
            Matcher q = QUANTITY.matcher(receipt.rows.get(i + 1));
            if (!q.find()) {
                continue;
            }

            String name = itemName(row);
            if (name.length() == 0) {
                continue;
            }
            double quantity;
            try {
                quantity = Double.parseDouble(q.group(1));
            } catch (NumberFormatException e) {
                continue;
            }
            receipt.items.add(new Item(name, quantity, trailingAmount(row)));
        }

        return receipt;
    }

    /**
     * Cut a run of text into printer rows.
     *
     * Loyverse relies on the printer wrapping, so a single "line" out of the
     * decoder is really several rows glued together.
     */
    public static List<String> reflow(String text) {
        List<String> rows = new ArrayList<String>();
        String clean = strip(text);
        if (clean.length() == 0) {
            rows.add("");
            return rows;
        }
        int start = 0;
        while (start < clean.length()) {
            int end = Math.min(start + COLUMNS, clean.length());
            rows.add(clean.substring(start, end));
            start = end;
        }
        return rows;
    }

    /**
     * Loyverse emits 0x7F where the peso sign should be. It is replaced by a
     * space, never removed, because it takes up a column on the paper and
     * dropping it would shift the rest of the row out of alignment.
     */
    private static String strip(String text) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // Replaced, never removed: it occupies a column on the paper, and
            // dropping it shifts every following character out of alignment.
            sb.append(c == 0x7F ? ' ' : c);
        }
        return sb.toString();
    }

    /** The name is everything before the run of spaces that pads the amount. */
    private static String itemName(String row) {
        String trimmed = row.trim();
        if (trimmed.length() == 0 || trimmed.startsWith("-") || trimmed.startsWith("- -")) {
            return "";
        }
        int gap = trimmed.indexOf("   ");
        String name = gap > 0 ? trimmed.substring(0, gap) : trimmed;
        name = name.trim();
        // Totals and payment rows are laid out like items but are not items.
        String lower = name.toLowerCase();
        if (lower.equals("total") || lower.equals("cash") || lower.equals("change")
                || lower.startsWith("employee") || lower.startsWith("pos")) {
            return "";
        }
        return name;
    }

    private static String trailingAmount(String row) {
        String trimmed = row.trim();
        int lastSpace = trimmed.lastIndexOf(' ');
        return lastSpace >= 0 ? trimmed.substring(lastSpace + 1) : "";
    }
}
