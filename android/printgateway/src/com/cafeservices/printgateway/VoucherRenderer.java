package com.cafeservices.printgateway;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the voucher slip, in the same graphic mode as the receipt above it.
 *
 * One slip, however many codes were sold: a heading, the codes listed under it,
 * and the terms stated once at the foot. Repeating the network and the times
 * beside every code padded the paper out and invited the reader to look for a
 * difference between blocks that was never there.
 *
 * Each code is flanked by dashes so a handwritten-looking string of digits
 * reads as a code to be typed, and so two codes cannot run together.
 *
 * Typography is the receipt's, not its own: the heading and codes take the bold
 * size the receipt gives its total - the one place that page already shouts -
 * and everything else its body size. A change to the receipt's type carries
 * here automatically.
 *
 * Widths are pixels, matching ReceiptRenderer: 58mm paper at 203dpi.
 */
public final class VoucherRenderer {

    /** Set for the duration of a render, as in ReceiptRenderer. */
    private static PaperProfile paper = PaperProfile.NARROW;

    private static final float TITLE_SIZE = ReceiptRenderer.TOTAL_SIZE;
    private static final float CODE_SIZE = ReceiptRenderer.TOTAL_SIZE;
    private static final float DETAIL_SIZE = ReceiptRenderer.BODY_SIZE;

    /**
     * Vertical steps, in the order they are drawn. Each has to clear the type
     * it follows, so the two large rows step by the receipt's wider pitch and
     * the body rows by its narrow one.
     */
    private static final int PAD_TOP = ReceiptRenderer.PITCH_WRAPPED;
    private static final int AFTER_TITLE = ReceiptRenderer.PITCH_TO_QUANTITY;
    private static final int CODE_PITCH = ReceiptRenderer.PITCH_TO_QUANTITY;
    private static final int AFTER_CODES = ReceiptRenderer.PITCH_TO_QUANTITY;
    private static final int DETAIL_PITCH = ReceiptRenderer.PITCH_WRAPPED;
    private static final int PAD_BOTTOM = ReceiptRenderer.PITCH_WRAPPED;

    private VoucherRenderer() {
    }

    private static Paint paint(float size, boolean bold) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.BLACK);
        p.setTextSize(size);
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF,
                bold ? Typeface.BOLD : Typeface.NORMAL));
        return p;
    }

    /** Reads as one slip whether it carries one code or five. */
    public static String title() {
        return "WiFi VOUCHER/S";
    }

    /** "Valid for 2 hours" - access time, counted from first login. */
    public static String validityLine(int accessHours) {
        return "Valid for " + accessHours + (accessHours == 1 ? " hour" : " hours");
    }

    /**
     * "WILL EXPIRE AFTER 24 HOURS" - the deadline for redeeming the code at
     * all, which is a different clock from the access time above and the one
     * customers lose money on, hence the shout.
     */
    public static String expiryLine(int expiryHours) {
        return "WILL EXPIRE AFTER " + expiryHours + (expiryHours == 1 ? " HOUR" : " HOURS");
    }

    /**
     * The slip printed when Omada could not be reached.
     *
     * Never digits. A stand-in code looks exactly like a real one to a guest,
     * who would type it, fail to get online, and blame the cafe - so the slip
     * says plainly that the code has to come from a person, and says nothing
     * else. A heading promising a voucher above a line admitting there is not
     * one only reads as a printer fault.
     */
    public static Bitmap renderUnavailable(PaperProfile onPaper) {
        paper = onPaper;
        Paint detailPaint = paint(DETAIL_SIZE, false);

        List<String> lines = new ArrayList<String>();
        lines.add("Please ask cashier");
        lines.add("for a wi-fi voucher");

        int height = PAD_TOP + DETAIL_PITCH * lines.size() + PAD_BOTTOM;
        Bitmap bitmap = Bitmap.createBitmap(paper.paperWidth, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);

        int y = PAD_TOP;
        for (String line : lines) {
            centre(canvas, line, y, detailPaint);
            y += DETAIL_PITCH;
        }
        return bitmap;
    }

    /**
     * @param codes        one entry per voucher sold
     * @param ssid         the network name a guest should join
     * @param accessHours  access time, counted from first login
     * @param expiryHours  how long an unredeemed code stays alive
     */
    public static Bitmap render(List<String> codes, String ssid, int accessHours,
                                int expiryHours, PaperProfile onPaper) {
        if (codes.isEmpty()) {
            return null;
        }
        paper = onPaper;

        Paint titlePaint = paint(TITLE_SIZE, true);
        Paint codePaint = paint(CODE_SIZE, false);
        Paint detailPaint = paint(DETAIL_SIZE, false);

        List<String> details = new ArrayList<String>();
        if (ssid.length() > 0) {
            details.add("Wi-Fi Name: " + ssid);
        }
        details.add(validityLine(accessHours));
        details.add(expiryLine(expiryHours));

        int height = PAD_TOP
                + AFTER_TITLE
                + CODE_PITCH * (codes.size() - 1)
                + AFTER_CODES
                + DETAIL_PITCH * details.size()
                + PAD_BOTTOM;

        Bitmap bitmap = Bitmap.createBitmap(paper.paperWidth, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);

        int y = PAD_TOP;
        centre(canvas, title(), y, titlePaint);
        y += AFTER_TITLE;
        for (int i = 0; i < codes.size(); i++) {
            centre(canvas, "--- " + codes.get(i) + " ---", y, codePaint);
            y += (i == codes.size() - 1) ? AFTER_CODES : CODE_PITCH;
        }
        for (String detail : details) {
            centre(canvas, detail, y, detailPaint);
            y += DETAIL_PITCH;
        }
        return bitmap;
    }

    /** Draws text centred on the paper, with y as its baseline. */
    private static void centre(Canvas canvas, String text, int y, Paint p) {
        float w = p.measureText(text);
        canvas.drawText(text, (paper.paperWidth - w) / 2f, y + p.getTextSize(), p);
    }
}
