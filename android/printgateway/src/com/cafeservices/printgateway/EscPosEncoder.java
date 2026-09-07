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
 * it carries, and encodes the gaps as bands one byte wide. That is not just
 * tidiness: a receipt is about 60% blank rows, and sending those full width
 * made our prints three times the size of Loyverse's for the same page. Over
 * Bluetooth that is seconds the cashier waits at the counter, so this packs
 * bands the same way - blank runs one byte wide, content trimmed to its right
 * edge.
 */
public final class EscPosEncoder {

    /** ESC @ - always sent, as Loyverse does, before anything configured. */
    private static final byte[] INIT = {0x1B, 0x40};

    /** ESC d 1 - feed, so the cut does not land on the last line of text. */
    private static final byte[] FEED = {0x1B, 0x64, 0x01};



    /**
     * Rows per band. Small enough for a modest printer buffer, large enough
     * that a long receipt does not become thousands of commands.
     */
    private static final int BAND_HEIGHT = 24;

    /**
     * Where grey becomes ink.
     *
     * Not mid-grey. The renderer antialiases, so glyph edges are grey, and a
     * cut at 128 throws most of that away: measured against a real Loyverse
     * print of the same receipt we burned 0.889x its ink per row, which is
     * exactly why our output looked lighter on the counter. Counting dots at
     * several cuts put 160 at 0.996x - the same weight as Loyverse.
     *
     * Re-measure with tools/ink-compare.mjs if the type or the font changes.
     */
    private static final int INK_THRESHOLD = 160;

    private EscPosEncoder() {
    }

    /**
     * Rows of blank paper per band. Blank runs cost one byte a row, so there
     * is no reason to cut them finely; this just keeps any single command
     * modest for a small printer buffer.
     */
    private static final int BLANK_BAND_MAX = 255;

    /**
     * @param bitmap    the receipt, already at the printer's dot width
     * @param openTill  mirror the drawer pulse the incoming job carried
     */
    public static byte[] encode(Bitmap bitmap, boolean openTill) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (openTill) {
            write(out, hex(Settings.drawerCommands()));
        }
        out.write(INIT, 0, INIT.length);
        // Loyverse leaves its "Initial ESC/POS commands" box empty by default;
        // a printer that needs a code page or a density set says so here.
        write(out, hex(Settings.initCommands()));

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        // How far the ink reaches on each row, or -1 for a row of blank paper.
        int[] rightmost = new int[height];
        for (int y = 0; y < height; y++) {
            int edge = -1;
            int base = y * width;
            for (int x = width - 1; x >= 0; x--) {
                if (isInk(pixels[base + x])) {
                    edge = x;
                    break;
                }
            }
            rightmost[y] = edge;
        }

        int y = 0;
        while (y < height) {
            if (rightmost[y] < 0) {
                int run = 0;
                while (y + run < height && rightmost[y + run] < 0) {
                    run++;
                }
                for (int done = 0; done < run; done += BLANK_BAND_MAX) {
                    int band = Math.min(BLANK_BAND_MAX, run - done);
                    // One byte wide: the paper still advances by the band's
                    // height, and blank paper needs no dots to say so. This is
                    // what Loyverse itself does, and it is most of the reason
                    // its prints are a quarter the size of a naive full-width
                    // encoding - a receipt is around 60% blank rows.
                    writeBandHeader(out, 1, band);
                    for (int i = 0; i < band; i++) {
                        out.write(0);
                    }
                }
                y += run;
                continue;
            }

            int end = y;
            while (end < height && rightmost[end] >= 0) {
                end++;
            }
            for (int top = y; top < end; top += BAND_HEIGHT) {
                int band = Math.min(BAND_HEIGHT, end - top);

                // Trim the band to the ink. Only the right edge can go: a
                // raster band always starts at the left margin, so anything
                // before the ink has to be sent as blank dots.
                int edge = 0;
                for (int i = 0; i < band; i++) {
                    edge = Math.max(edge, rightmost[top + i]);
                }
                int widthBytes = (edge + 8) / 8;

                writeBandHeader(out, widthBytes, band);
                for (int i = 0; i < band; i++) {
                    int base = (top + i) * width;
                    for (int xByte = 0; xByte < widthBytes; xByte++) {
                        int bits = 0;
                        for (int bit = 0; bit < 8; bit++) {
                            int x = xByte * 8 + bit;
                            if (x < width && isInk(pixels[base + x])) {
                                bits |= 0x80 >> bit;
                            }
                        }
                        out.write(bits);
                    }
                }
            }
            y = end;
        }

        out.write(FEED, 0, FEED.length);
        write(out, hex(Settings.cutterCommands()));
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, byte[] bytes) {
        if (bytes.length > 0) {
            out.write(bytes, 0, bytes.length);
        }
    }

    /**
     * Read a command the way a printer manual quotes it and Loyverse takes it:
     * hex bytes, comma or space separated, e.g. "1D,56,42,00".
     *
     * Anything unparseable is dropped rather than guessed at - a stray byte in
     * a control sequence prints garbage across the paper, and an empty box is
     * a perfectly ordinary setting.
     */
    static byte[] hex(String text) {
        if (text == null || text.trim().length() == 0) {
            return new byte[0];
        }
        String[] parts = text.trim().split("[, ]+");
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (String part : parts) {
            try {
                out.write(Integer.parseInt(part.trim(), 16) & 0xFF);
            } catch (NumberFormatException e) {
                return new byte[0];
            }
        }
        return out.toByteArray();
    }

    /** GS v 0 m xL xH yL yH - one raster band, printed at the left margin. */
    private static void writeBandHeader(ByteArrayOutputStream out, int widthBytes, int height) {
        out.write(0x1D);
        out.write(0x76);
        out.write(0x30);
        out.write(0x00);
        out.write(widthBytes & 0xFF);
        out.write((widthBytes >> 8) & 0xFF);
        out.write(height & 0xFF);
        out.write((height >> 8) & 0xFF);
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
