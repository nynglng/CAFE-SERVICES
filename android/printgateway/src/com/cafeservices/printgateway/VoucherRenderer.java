package com.cafeservices.printgateway;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;

import java.util.List;

/**
 * Draws the voucher slip, in the same graphic mode as the receipt above it.
 *
 * This is the piece the customer carries to their table and types off, so the
 * code is set large and alone with nothing crowding it, and everything else -
 * how long the access lasts, which network to join, when the code dies - sits
 * quietly underneath in one size.
 *
 * Widths are pixels, matching ReceiptRenderer: 80mm paper at 203dpi.
 */
public final class VoucherRenderer {

    private static final int PAPER_WIDTH = ReceiptRenderer.PAPER_WIDTH;

    private static final float LABEL_SIZE = 26f;
    private static final float CODE_SIZE = 76f;
    private static final float DETAIL_SIZE = 28f;
    private static final float COUNT_SIZE = 24f;

    /**
     * Vertical room around each part, in the order they are drawn. Each is the
     * step to the next line, so it has to clear the type it follows - the code
     * is set at 76px, so anything under that would print the validity line
     * through the middle of it.
     */
    private static final int PAD_TOP = 34;
    private static final int AFTER_COUNT = 34;
    private static final int AFTER_LABEL = 62;
    private static final int AFTER_CODE = 104;
    private static final int DETAIL_PITCH = 36;
    private static final int GAP_BETWEEN_VOUCHERS = 34;
    private static final int PAD_BOTTOM = 28;

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

    /**
     * @param codes one entry per voucher sold
     * @param ssid  the network name a guest should join
     * @param hours access time, counted from first login
     */
    public static Bitmap render(List<String> codes, String ssid, int hours) {
        if (codes.isEmpty()) {
            return null;
        }

        Paint label = paint(LABEL_SIZE, false);
        Paint code = paint(CODE_SIZE, true);
        Paint detail = paint(DETAIL_SIZE, false);
        Paint count = paint(COUNT_SIZE, false);

        boolean many = codes.size() > 1;
        String validity = "Valid for " + hours + (hours == 1 ? " hour" : " hours");
        String network = "Wi-Fi Name: " + ssid;
        String expiry = "Will expire after 24 hours.";

        int perVoucher = PAD_TOP
                + (many ? AFTER_COUNT : 0)
                + AFTER_LABEL
                + AFTER_CODE
                + DETAIL_PITCH * 3
                + PAD_BOTTOM;
        int height = perVoucher * codes.size()
                + GAP_BETWEEN_VOUCHERS * (codes.size() - 1);

        Bitmap bitmap = Bitmap.createBitmap(PAPER_WIDTH, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);

        int y = 0;
        for (int i = 0; i < codes.size(); i++) {
            if (i > 0) {
                // A rule between vouchers, so two codes on one strip of paper
                // cannot be mistaken for one voucher with a stray number on it.
                rule(canvas, y + GAP_BETWEEN_VOUCHERS / 2);
                y += GAP_BETWEEN_VOUCHERS;
            }
            y += PAD_TOP;
            if (many) {
                centre(canvas, "Voucher " + (i + 1) + " of " + codes.size(), y, count);
                y += AFTER_COUNT;
            }
            centre(canvas, "VOUCHER CODE:", y, label);
            y += AFTER_LABEL;
            centre(canvas, codes.get(i), y, code);
            y += AFTER_CODE;
            centre(canvas, validity, y, detail);
            y += DETAIL_PITCH;
            centre(canvas, network, y, detail);
            y += DETAIL_PITCH;
            centre(canvas, expiry, y, detail);
            y += DETAIL_PITCH + PAD_BOTTOM;
        }
        return bitmap;
    }

    /** Draws text centred on the paper, with y as its baseline. */
    private static void centre(Canvas canvas, String text, int y, Paint p) {
        float w = p.measureText(text);
        canvas.drawText(text, (PAPER_WIDTH - w) / 2f, y + p.getTextSize(), p);
    }

    private static void rule(Canvas canvas, int y) {
        Paint p = new Paint();
        p.setColor(Color.BLACK);
        for (int x = 2; x <= PAPER_WIDTH - 2; x += 8) {
            canvas.drawRect(x, y, x + 3, y + 2, p);
        }
    }
}
