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
 * differently - text at 32 characters, graphics at a pixel width - so the rows
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
 *   name column      wraps at x = 291 (bracketed to 286..297)
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

    /**
     * The paper this render is drawing on, set for the duration of render().
     *
     * Follows the same pattern as textPaint below: the layout and the blocks
     * both need it, and threading it through every signature bought nothing -
     * one job is drawn at a time.
     */
    private static PaperProfile paper = PaperProfile.NARROW;

    private static final int MARGIN_LEFT = 2;

    /**
     * Where a name has to stop so it cannot run into the amount.
     *
     * Bracketed by two deliberately long names printed at 58mm:
     *
     *   fits        "ABCDEFGHIJKLMNOPQRSTUV"        ink ends at 285
     *   must not    "aaa bbb ccc ddd eee fff ggg"   would reach ~298
     *
     * so the real threshold is somewhere in 286..297, and 291 sits in the
     * middle of that window rather than against either edge. The amount's ink
     * begins at 308, so this also leaves the column clear.
     */


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

    /**
     * Row rhythm, measured from a real 58mm capture: consecutive rows sit
     * about 36px apart, a blank row costs 19, a divider hangs 4px under the
     * row above with 28px of air after it, the store name is followed by 53,
     * and the bold Total takes 49.
     */
    private static final int PITCH_ROW = 36;
    private static final int PITCH_BLANK = 19;
    private static final int PITCH_AFTER_DIVIDER = 28;
    private static final int PITCH_TITLE = 53;
    private static final int PITCH_TOTAL = 49;

    public static final int PITCH_WRAPPED = 31;
    public static final int PITCH_TO_QUANTITY = 37;
    public static final int PITCH_BETWEEN_ITEMS = 56;

    private static final char SPACE = 32;
    private static final char DASH = 45;
    private static final char COMMA = 44;
    private static final char DOT = 46;
    private static final char HASH = 35;

    /** The body paint, so row layout can measure names for wrapping. */
    private static Paint textPaint;

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
        paper = receipt.paper;

        Paint text = body(false, BODY_SIZE);
        Paint bold = body(true, HEADER_SIZE);
        Paint total = body(true, TOTAL_SIZE);
        textPaint = text;

        calibrate(text);
        List<Block> blocks = layout(plan, receipt, header);

        int height = 0;
        for (Block b : blocks) {
            height = Math.max(height, b.y + b.height());
        }
        height += 12;

        Bitmap bitmap = Bitmap.createBitmap(paper.paperWidth, height, Bitmap.Config.ARGB_8888);
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
     * References re-measured at 58mm with tools/measure-capture.mjs, which
     * reports where Loyverse's own ink actually falls. The earlier numbers
     * came from an 80mm capture and cited strings long enough that they cannot
     * appear unbroken on this paper at all.
     *
     * Ink extent is not identical to measureText - side bearings differ - so
     * a couple of pixels out is fine. Tens of pixels means the type has moved.
     */
    private static void calibrate(Paint text) {
        android.util.Log.i(PrintServerService.TAG, "type check:"
                + " size=" + text.getTextSize()
                + " upper15=" + text.measureText("CAFE STORE NAME") + " (real 197)"
                + " upper19=" + text.measureText("ABCDEFGHIJKLMNOPQRS") + " (real 286)");
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
                c.drawText(left, (paper.paperWidth - w) / 2f, baseline, p);
                return;
            }
            c.drawText(left, MARGIN_LEFT, baseline, p);
            if (right != null && right.length() > 0) {
                float w = p.measureText(right);
                c.drawText(right, paper.marginRight - w, baseline, p);
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
            for (int x = MARGIN_LEFT; x <= paper.marginRight; x += 4) {
                c.drawRect(x, y, x + 2, y + 2, p);
            }
        }
    }

    /**
     * Replay the receipt row by row, in the order Loyverse sent them.
     *
     * An earlier version rebuilt the page from recognised pieces - employee,
     * items, Total, Cash - and silently dropped everything else. On a real
     * cafe sale that lost the PWD discount, the Card payment and the address,
     * leaving a receipt whose figures did not add up. Anything unrecognised
     * now passes through untouched, so a row type nobody anticipated survives.
     *
     * Item names are the one exception. Loyverse broke those to fit its
     * 22-character text field, but graphic mode breaks on pixels and lands
     * elsewhere, so an item's rows are replaced by its rejoined name
     * re-wrapped against the paper's name column.
     */
    private static List<Block> layout(SalePlan plan, ReceiptParser.Receipt receipt,
                                      String header) {
        List<Block> blocks = new ArrayList<Block>();
        int y = 10;

        // Row index -> the item starting there, so its rows can be redrawn.
        java.util.Map<Integer, ReceiptParser.Item> startsAt =
                new java.util.HashMap<Integer, ReceiptParser.Item>();
        for (ReceiptParser.Item item : receipt.items) {
            startsAt.put(Integer.valueOf(item.firstRow), item);
        }

        boolean firstDrawn = true;
        for (int i = 0; i < receipt.rows.size(); i++) {
            ReceiptParser.Item item = startsAt.get(Integer.valueOf(i));

            if (item != null) {
                i = item.lastRow; // its rows are spoken for either way
                if (SalePlan.isWifi(item.name)) {
                    continue; // replaced by the voucher below
                }
                List<String> lines = wrap(item.name, textPaint,
                        paper.nameColumnRight - MARGIN_LEFT);
                for (int n = 0; n < lines.size(); n++) {
                    Row r = new Row();
                    r.left = lines.get(n);
                    r.right = (n == 0) ? PESO + item.amount : null;
                    r.y = y;
                    blocks.add(r);
                    y += PITCH_ROW;
                }
                Row qty = new Row();
                qty.left = item.wholeQuantity() + " x " + PESO + item.unitAmount;
                qty.y = y;
                blocks.add(qty);
                y += PITCH_ROW;
                firstDrawn = false;
                continue;
            }

            String raw = receipt.rows.get(i);
            if (raw.trim().length() == 0) {
                y += PITCH_BLANK;
                continue;
            }
            if (isDivider(raw)) {
                y += 4;
                Divider d = new Divider();
                d.y = y;
                blocks.add(d);
                y += PITCH_AFTER_DIVIDER;
                continue;
            }

            Row r = new Row();
            String text = restorePeso(raw);
            // Only a money value or the receipt number is pinned to the right
            // edge. Trailing padding cannot be used to tell - the rows arrive
            // already trimmed - and splitting on any wide gap threw "POS: POS
            // 1" to opposite edges of the paper.
            int cut = lastGap(text);
            boolean pinnedRight = cut > 0 && pinsRight(text.substring(cut).trim());
            if (pinnedRight) {
                r.left = collapseSpaces(text.substring(0, cut).trim());
                r.right = text.substring(cut).trim();
            } else {
                r.left = collapseSpaces(text.trim());
                r.centred = isCentred(raw);
            }
            r.isTotal = r.left.equalsIgnoreCase("Total");
            r.isBold = firstDrawn;
            r.y = y;
            blocks.add(r);
            y += r.isTotal ? PITCH_TOTAL : (firstDrawn ? PITCH_TITLE : PITCH_ROW);
            firstDrawn = false;
        }

        return blocks;
    }

    /**
     * Whether a trailing value belongs against the right edge: an amount, or
     * the receipt number in the footer. Anything else is part of the sentence
     * on the left and stays there.
     */
    private static boolean pinsRight(String value) {
        if (value.length() == 0) {
            return false;
        }
        if (value.indexOf(PESO) >= 0) {
            return true;
        }
        return value.charAt(0) == HASH;
    }

    /** A rule row: dashes and spaces, nothing else. */
    private static boolean isDivider(String row) {
        String t = row.trim();
        if (t.length() < 3) {
            return false;
        }
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c != DASH && c != SPACE) {
                return false;
            }
        }
        return true;
    }

    /** Loyverse centres by padding both sides, so balanced padding means centred. */
    private static boolean isCentred(String row) {
        int lead = 0;
        while (lead < row.length() && row.charAt(lead) == SPACE) {
            lead++;
        }
        int trail = 0;
        while (trail < row.length() - lead && row.charAt(row.length() - 1 - trail) == SPACE) {
            trail++;
        }
        return lead >= 2 && Math.abs(lead - trail) <= 3;
    }

    /**
     * Index just past the last run of two or more spaces, which is where a
     * value pinned to the right begins. Returns -1 when there is no such run.
     */
    private static int lastGap(String text) {
        String t = trimRight(text);
        for (int i = t.length() - 2; i > 0; i--) {
            if (t.charAt(i) == SPACE && t.charAt(i - 1) == SPACE) {
                int end = i;
                while (end < t.length() && t.charAt(end) == SPACE) {
                    end++;
                }
                return end;
            }
        }
        return -1;
    }

    private static String trimRight(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == SPACE) {
            end--;
        }
        return s.substring(0, end);
    }

    /**
     * Put the peso sign back in front of every amount.
     *
     * The decoder turns Loyverse's 0x7F into a space to keep the columns
     * straight, and that space is exactly where the symbol belongs.
     */
    private static String restorePeso(String row) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < row.length(); i++) {
            char c = row.charAt(i);
            if (c == SPACE && i + 1 < row.length() && startsAmount(row, i + 1)) {
                sb.append(PESO);
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** True when a digit run with two decimal places begins here. */
    private static boolean startsAmount(String row, int at) {
        int i = at;
        boolean digits = false;
        while (i < row.length()
                && (Character.isDigit(row.charAt(i)) || row.charAt(i) == COMMA)) {
            digits = true;
            i++;
        }
        if (!digits || i + 2 >= row.length() || row.charAt(i) != DOT) {
            return false;
        }
        return Character.isDigit(row.charAt(i + 1)) && Character.isDigit(row.charAt(i + 2));
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
