package com.cafeservices.printgateway;

import android.graphics.Bitmap;

import java.io.ByteArrayOutputStream;

/**
 * Turns the drawn receipt back into ESC/POS, the way Loyverse sends it.
 *
 * The printer at the cafe could be any model, so the safest thing to send is
 * what already works there: Loyverse's own graphic mode. A captured print
 * opens and closes like this -
 *
 *     1B 70 00 19 FA    cash drawer pulse (only when the sale had one)
 *     1B 40             initialise
 *     1D 76 30 00 ...   raster bands, the picture itself
 *     1B 64 01          feed one line
 *     1D 56 42 00       cut
 *
 * - and the two outer commands are exactly the Drawer and Cutter fields in
 * Loyverse's printer settings, so a printer configured for Loyverse is
 * configured for this.
 *
 * Loyverse slices its page into many small bands, each only as wide as the row
 * it carries. That is its own optimisation, not a requirement: a printer draws
 * the same picture from bands of any height, so this sends full-width bands of
 * a fixed height, which is simpler and easier to check.
 */
public final class EscPosEncoder {

    /** ESC p 0 25 250 - the pulse Loyverse opens a sale's print with. */
    private static final byte[] DRAWER = {0x1B, 0x70, 0x00, 0x19, (byte) 0xFA};

    /** ESC @ */
    private static final byte[] INIT = {0x1B, 0x40};

    /** ESC d 1 - feed, so the cut does not land on the last line of text. */
    private static final byte[] FEED = {0x1B, 0x64, 0x01};

    /** GS V B 0 */
    private static final byte[] CUT = {0x1D, 0x56, 0x42, 0x00};

    /**
     * Rows per band. Small enough for a modest printer buffer, large enough
     * that a long receipt does not become thousands of commands.
     */
    private static final int BAND_HEIGHT = 24;

    /** Anything darker than mid-grey is ink; the paper has no greys. */
    private static final int INK_THRESHOLD = 128;

    private EscPosEncoder() {
    }

    /**
     * @param bitmap    the receipt, already at the printer's dot width
     * @param openTill  mirror the drawer pulse the incoming job carried
     */
    public static byte[] encode(Bitmap bitmap, boolean openTill) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (openTill) {
            out.write(DRAWER, 0, DRAWER.length);
        }
        out.write(INIT, 0, INIT.length);

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int widthBytes = (width + 7) / 8;

        int[] row = new int[width];
        for (int top = 0; top < height; top += BAND_HEIGHT) {
            int bandHeight = Math.min(BAND_HEIGHT, height - top);

            // GS v 0 m xL xH yL yH, then one bit per dot, MSB first.
            out.write(0x1D);
            out.write(0x76);
            out.write(0x30);
            out.write(0x00);
            out.write(widthBytes & 0xFF);
            out.write((widthBytes >> 8) & 0xFF);
            out.write(bandHeight & 0xFF);
            out.write((bandHeight >> 8) & 0xFF);

            for (int y = 0; y < bandHeight; y++) {
                bitmap.getPixels(row, 0, width, 0, top + y, width, 1);
                for (int xByte = 0; xByte < widthBytes; xByte++) {
                    int bits = 0;
                    for (int bit = 0; bit < 8; bit++) {
                        int x = xByte * 8 + bit;
                        if (x < width && isInk(row[x])) {
                            bits |= 0x80 >> bit;
                        }
                    }
                    out.write(bits);
                }
            }
        }

        out.write(FEED, 0, FEED.length);
        out.write(CUT, 0, CUT.length);
        return out.toByteArray();
    }

    /**
     * Whether a pixel should burn.
     *
     * Luminance rather than a plain average: the renderer antialiases text, and
     * grey edges that fall on the wrong side of the line either fatten the type
     * or eat its thin strokes.
     */
    private static boolean isInk(int pixel) {
        int alpha = (pixel >>> 24) & 0xFF;
        if (alpha < 128) {
            return false; // transparent counts as paper
        }
        int r = (pixel >> 16) & 0xFF;
        int g = (pixel >> 8) & 0xFF;
        int b = pixel & 0xFF;
        int luminance = (r * 77 + g * 151 + b * 28) >> 8;
        return luminance < INK_THRESHOLD;
    }
}
