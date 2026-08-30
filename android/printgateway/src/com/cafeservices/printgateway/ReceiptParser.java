package com.cafeservices.printgateway;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls the sale out of a Loyverse text-mode print.
 *
 * With print mode set to Text, Loyverse writes fixed 48-column rows and sends
 * no line breaks between them - the printer is expected to wrap - so the stream
 * has to be re-cut into rows before any of it makes sense.
 *
 * An item spans two or more rows:
 *
 *     seasalt                                   170.00
 *      1 x   170.00
 *
 * and a name too long for the field continues on rows of its own before the
 * quantity row arrives.
 *
 * Two details make the parsing reliable. Items begin at column 0, while every
 * other row - Employee, POS, the quantity, Total, Cash - begins with a space.
 * And the name field is a fixed 38 characters, which is what tells us how a
 * wrapped name was broken; see joinName.
 */
public final class ReceiptParser {

    /** Loyverse's row width in text mode on 80mm paper. */
    public static final int COLUMNS = 48;

    /**
     * Width of the item-name field before the amount column.
     *
     * Measured from a receipt carrying names far longer than the field. It is
     * worth re-checking if amounts ever grow wider (a five-figure total, say),
     * since the field may be sized around them.
     */
    public static final int NAME_FIELD = 38;

    /** "seasalt                       170.00" - a name and its amount. */
    private static final Pattern ITEM =
            Pattern.compile("^(\\S.*?)\\s\\s+([0-9,]+\\.[0-9]{2})\\s*$");

    /** " 1 x   170.00" - the quantity row belonging to the item above. */
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
        /** The reflowed rows, as the printer would have wrapped them. */
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
            receipt.rows.addAll(reflow(line.text));
        }

        for (int i = 0; i < receipt.rows.size(); i++) {
            String row = receipt.rows.get(i);

            if (receipt.receiptNumber.length() == 0) {
                Matcher number = RECEIPT_NUMBER.matcher(row);
                if (number.find()) {
                    receipt.receiptNumber = number.group();
                }
            }

            Matcher item = ITEM.matcher(row);
            if (!item.matches()) {
                continue;
            }

            String name = item.group(1).trim();
            String amount = item.group(2);

            // Continuation rows carry the rest of the name. They also start at
            // column 0, and run until the quantity row.
            int j = i + 1;
            while (j < receipt.rows.size()) {
                String next = receipt.rows.get(j);
                if (next.trim().length() == 0
                        || next.startsWith(" ")
                        || QUANTITY.matcher(next).find()) {
                    break;
                }
                name = joinName(name, next.trim());
                j++;
            }

            // Without a quantity row this was not an item at all.
            if (j >= receipt.rows.size()) {
                continue;
            }
            Matcher quantity = QUANTITY.matcher(receipt.rows.get(j));
            if (!quantity.find()) {
                continue;
            }

            double count;
            try {
                count = Double.parseDouble(quantity.group(1));
            } catch (NumberFormatException e) {
                continue;
            }
            receipt.items.add(new Item(name, count, amount));
            i = j;
        }

        return receipt;
    }

    /**
     * Rejoin a name Loyverse split across rows.
     *
     * The name field is a fixed width. If the part already on the row stops
     * short of it, the break happened at a space that was then swallowed, so a
     * space goes back in. If it fills the field, the break fell mid-word and
     * the halves join directly. Getting this backwards silently corrupts every
     * long item name, so it is worth stating plainly.
     */
    static String joinName(String soFar, String continuation) {
        return soFar.length() >= NAME_FIELD
                ? soFar + continuation
                : soFar + " " + continuation;
    }

    /**
     * Cut a run of text into printer rows.
     *
     * Loyverse relies on the printer wrapping, so a single "line" out of the
     * decoder is really several rows glued together.
     */
    public static List<String> reflow(String text) {
        List<String> rows = new ArrayList<String>();
        String clean = normalise(text);
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
     * Loyverse emits 0x7F where the peso sign belongs, its code page having no
     * glyph for it. It is replaced by a space, never removed, because it takes
     * a column on the paper and dropping it shifts the rest of the row.
     */
    private static String normalise(String text) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            sb.append(c == 0x7F ? ' ' : c);
        }
        return sb.toString();
    }
}
