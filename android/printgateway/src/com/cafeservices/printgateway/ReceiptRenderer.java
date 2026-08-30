package com.cafeservices.printgateway;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws a receipt the way Loyverse's own graphic mode draws it.
 *
 * The gateway is handed text and has to emit a picture, and the two modes wrap
 * differently - text at 48 characters, graphics at a pixel width - so the rows
 * cannot simply be copied onto a canvas. Names are rebuilt by the parser and
 * re-wrapped here against the real column width.
 *
 * Every measurement below was taken off real captures from the site with
 * tools/measure-capture.mjs, which reassembles Loyverse's own graphic output
 * and reports where the ink actually falls:
 *
 *   paper            384px wide, 58mm at 203dpi
 *   left margin      x = 2
 *   amounts          right-aligned to x = 382, ink starting at x = 295
 *   name column      wraps at x = 287
 *   body text        17px tall, 23px with descenders
 *   total row        26px, bold
 *   dividers         dotted, 2-3px tall, full width
 *   line pitch       31px for a wrapped name, 37px to the quantity row,
 *                    56px between items
 *
 * The client's printer is 58mm. An earlier calibration assumed 80mm and every
 * width here was wrong by a third; the type sizes and line pitches, though,
 * turned out to be identical between the two - only the widths changed.
 */
public final class ReceiptRenderer {

    public static final int PAPER_WIDTH = 384;

    private static final int MARGIN_LEFT = 2;
    private static final int MARGIN_RIGHT = 382;
    /**
     * Where a name has to stop so it cannot run into the amount.
     *
     * Measured: on every item row the amount's ink begins at x = 295, and the
     * longest name seen ("WHITECHOCO ICED16") ends at 239. The old 80mm
     * calibration put this 8px clear of the amount column, so the same rule
     * gives 287 here.
     *
     * UNVERIFIED: no captured receipt has a name long enough to wrap at 58mm,
     * so the true break point is only known to lie between 240 and 295. Pin it
     * properly when a long name turns up - the 80mm value was fixed to a
     * two-pixel window this way, and this one deserves the same.
     */
    private static final int NAME_COLUMN_RIGHT = 287;

    /**
     * Loyverse prints the peso sign before every amount, but sends it as 0x7F
     * because its code page has no glyph for it. The parser turns that into a
     * space to keep the columns straight, so the symbol is put back here.
     */
    private static final String PESO = "₱";

    /**
     * Chosen so digits measure ~17px tall, matching the captured print.
     *
     * The receipt gets by on two sizes: body, and a bold total. The voucher
     * below it reuses these rather than keeping a scale of its own, so the
     * whole printout reads as one document and the two cannot drift apart.
     */
    public static final float BODY_SIZE = 24f;
    public static final float TOTAL_SIZE = 34f;
    private static final float HEADER_SIZE = 24f;

    public static final int PITCH_WRAPPED = 31;
    public static final int PITCH_TO_QUANTITY = 37;
    public static final int PITCH_BETWEEN_ITEMS = 56;

    private ReceiptRenderer() {
    }

    private static Paint body(boolean bold, float size) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.BLACK);
        p.setTextSize(size);
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF,
                bold ? Typeface.BOLD : Typeface.NORMAL));
        return p;
    }

    /**
     * Render the receipt part of a sale. Returns null when the plan says no
     * receipt should print.
     */
    public static Bitmap render(SalePlan plan, ReceiptParser.Receipt receipt, String header) {
        if (!plan.printReceipt) {
            return null;
        }

        Paint text = body(false, BODY_SIZE);
        Paint bold = body(true, HEADER_SIZE);
        Paint total = body(true, TOTAL_SIZE);

        calibrate(text);
        List<Block> blocks = layout(plan, receipt, header, text, total);

        int height = 0;
        for (Block b : blocks) {
            height = Math.max(height, b.y + b.height());
        }
        height += 12;

        Bitmap bitmap = Bitmap.createBitmap(PAPER_WIDTH, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        for (Block b : blocks) {
            b.draw(canvas, text, bold, total);
        }
        return bitmap;
    }

    /**
     * Report how wide our type is against known measurements from a real
     * print, so the size can be corrected when it drifts.
     *
     * References taken from the captured print: the long unbroken name is
     * 473px at 41 characters, and "...be a better person to" is 443px. Both
     * should land within a pixel or two of the numbers logged here.
     */
    private static void calibrate(Paint text) {
        android.util.Log.i(PrintServerService.TAG, "type check:"
                + " size=" + text.getTextSize()
                + " long39=" + text.measureText("longname longname be a better person to")
                + " (real 443)"
                + " long42=" + text.measureText("longname longname be a better person to be")
                + " jv41=" + text.measureText("jvjevjxekxkxkrbckebkxbskcsbksbcksbckrbxkt") + " (real 473)"
                + " jv42=" + text.measureText("jvjevjxekxkxkrbckebkxbskcsbksbcksbckrbxktb"));
    }

    // --- layout ------------------------------------------------------------

    private abstract static class Block {
        int y;

        abstract int height();

        abstract void draw(Canvas c, Paint text, Paint bold, Paint total);
    }

    private static final class Row extends Block {
        String left;
        String right;
        boolean isBold;
        boolean isTotal;
        boolean centred;

        @Override
        int height() {
            return isTotal ? 26 : 18;
        }

        @Override
        void draw(Canvas c, Paint text, Paint bold, Paint total) {
            Paint p = isTotal ? total : (isBold ? bold : text);
            float baseline = y + height();
            if (centred) {
                float w = p.measureText(left);
                c.drawText(left, (PAPER_WIDTH - w) / 2f, baseline, p);
                return;
            }
            c.drawText(left, MARGIN_LEFT, baseline, p);
            if (right != null && right.length() > 0) {
                float w = p.measureText(right);
                c.drawText(right, MARGIN_RIGHT - w, baseline, p);
            }
        }
    }

    private static final class Divider extends Block {
        @Override
        int height() {
            return 3;
        }

        @Override
        void draw(Canvas c, Paint text, Paint bold, Paint total) {
            Paint p = new Paint();
            p.setColor(Color.BLACK);
            // Dotted, matching the printed rule: a dot every 4px.
            for (int x = MARGIN_LEFT; x <= MARGIN_RIGHT; x += 4) {
                c.drawRect(x, y, x + 2, y + 2, p);
            }
        }
    }

    private static List<Block> layout(SalePlan plan, ReceiptParser.Receipt receipt,
                                      String header, Paint text, Paint total) {
        List<Block> blocks = new ArrayList<Block>();
        int y = 10;

        Row title = new Row();
        title.left = header;
        title.isBold = true;
        title.centred = true;
        title.y = y;
        blocks.add(title);
        y += 23 + 33;

        for (String meta : metaRows(receipt)) {
            Row r = new Row();
            r.left = meta;
            r.y = y;
            blocks.add(r);
            y += 31;
        }

        Divider top = new Divider();
        top.y = y + 4;
        blocks.add(top);
        y += 3 + 25;

        for (ReceiptParser.Item item : receipt.items) {
            if (SalePlan.isWifi(item.name)) {
                continue; // replaced by the voucher below
            }
            List<String> lines = wrap(item.name, text, NAME_COLUMN_RIGHT - MARGIN_LEFT);
            for (int i = 0; i < lines.size(); i++) {
                Row r = new Row();
                r.left = lines.get(i);
                r.right = (i == 0) ? PESO + item.amount : null;
                r.y = y;
                blocks.add(r);
                y += PITCH_WRAPPED;
            }
            Row qty = new Row();
            qty.left = item.wholeQuantity() + " x " + PESO + item.amount;
            qty.y = y - PITCH_WRAPPED + PITCH_TO_QUANTITY;
            blocks.add(qty);
            y = qty.y + PITCH_BETWEEN_ITEMS;
        }

        Divider mid = new Divider();
        mid.y = y - 20;
        blocks.add(mid);
        y += 8;

        for (String[] pair : totalRows(receipt)) {
            Row r = new Row();
            r.left = pair[0];
            r.right = PESO + pair[1];
            r.isTotal = "Total".equalsIgnoreCase(pair[0]);
            r.y = y;
            blocks.add(r);
            y += r.isTotal ? 68 : 41;
        }

        Divider bottom = new Divider();
        bottom.y = y - 18;
        blocks.add(bottom);
        y += 8;

        Row footer = new Row();
        footer.left = footerLeft(receipt);
        footer.right = receipt.receiptNumber;
        footer.y = y;
        blocks.add(footer);

        return blocks;
    }

    /** Rows above the first divider: employee, POS and anything like them. */
    private static List<String> metaRows(ReceiptParser.Receipt receipt) {
        List<String> out = new ArrayList<String>();
        for (String row : receipt.rows) {
            String t = row.trim();
            if (t.startsWith("Employee") || t.startsWith("POS:")) {
                out.add(collapseSpaces(t));
            }
        }
        return out;
    }

    /** Loyverse collapses the padding its text layout leaves between fields. */
    private static String collapseSpaces(String text) {
        StringBuilder sb = new StringBuilder();
        boolean lastWasSpace = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean isSpace = (c == ' ');
            if (!isSpace || !lastWasSpace) {
                sb.append(c);
            }
            lastWasSpace = isSpace;
        }
        return sb.toString();
    }

    private static List<String[]> totalRows(ReceiptParser.Receipt receipt) {
        List<String[]> out = new ArrayList<String[]>();
        for (String row : receipt.rows) {
            String t = row.trim();
            for (String label : new String[]{"Total", "Cash", "Change"}) {
                if (t.startsWith(label)) {
                    String amount = t.substring(label.length()).trim();
                    out.add(new String[]{label, amount});
                }
            }
        }
        return out;
    }

    private static String footerLeft(ReceiptParser.Receipt receipt) {
        for (String row : receipt.rows) {
            String t = row.trim();
            if (t.contains("/") && t.contains(":") && receipt.receiptNumber.length() > 0
                    && t.endsWith(receipt.receiptNumber)) {
                return t.substring(0, t.length() - receipt.receiptNumber.length()).trim();
            }
        }
        return "";
    }

    /**
     * Wrap to the column width in pixels, breaking at spaces where possible and
     * mid-word only when a single word will not fit - which is what Loyverse's
     * own output does.
     */
    static List<String> wrap(String name, Paint paint, float widthPx) {
        List<String> lines = new ArrayList<String>();
        String remaining = name.trim();

        while (remaining.length() > 0) {
            int fit = paint.breakText(remaining, true, widthPx, null);
            if (fit >= remaining.length()) {
                lines.add(remaining);
                break;
            }
            int space = remaining.lastIndexOf(' ', fit);
            int cut = (space > 0) ? space : fit;
            lines.add(remaining.substring(0, cut).trim());
            remaining = remaining.substring(cut).trim();
        }
        return lines;
    }
}
