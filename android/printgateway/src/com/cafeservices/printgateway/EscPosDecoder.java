package com.cafeservices.printgateway;

import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a raw ESC/POS byte stream into readable text.
 *
 * A thermal printer is not sent a document; it is sent a stream of characters
 * interleaved with control codes that change alignment, size and emphasis. To
 * restructure what Loyverse prints we first have to understand it, so this
 * walks the stream, applies the commands it recognises, and skips the payload
 * of the ones it does not - which is what stops binary logo data spilling into
 * the text.
 *
 * The command set here is the common subset every ESC/POS printer supports,
 * which is all a Loyverse receipt uses.
 */
public final class EscPosDecoder {

    private static final byte ESC = 0x1B;
    private static final byte GS = 0x1D;
    private static final byte FS = 0x1C;
    private static final byte LF = 0x0A;
    private static final byte CR = 0x0D;
    private static final byte HT = 0x09;

    public static final int ALIGN_LEFT = 0;
    public static final int ALIGN_CENTER = 1;
    public static final int ALIGN_RIGHT = 2;

    /** One line of decoded output, with the styling active when it printed. */
    public static final class Line {
        public final String text;
        public final int align;
        public final boolean bold;
        public final boolean doubleWidth;
        public final boolean doubleHeight;

        Line(String text, int align, boolean bold, boolean dw, boolean dh) {
            this.text = text;
            this.align = align;
            this.bold = bold;
            this.doubleWidth = dw;
            this.doubleHeight = dh;
        }

        public boolean isBig() {
            return doubleWidth || doubleHeight;
        }
    }

    /** A GS v 0 raster block: 1 bit per pixel, MSB first, black = 1. */
    public static final class Raster {
        public final int widthBytes;
        public final int height;
        public final byte[] bits;
        /** Justification in force when this band printed. */
        public final int align;

        Raster(int widthBytes, int height, byte[] bits, int align) {
            this.widthBytes = widthBytes;
            this.height = height;
            this.bits = bits;
            this.align = align;
        }

        public int widthPixels() {
            return widthBytes * 8;
        }
    }

    public static final class Result {
        /** Image bands, in the order they were printed. */
        public final List<Raster> rasters = new ArrayList<Raster>();

        public final List<Line> lines = new ArrayList<Line>();
        /** Commands that carry meaning beyond text: cuts, drawers, logos. */
        public final List<String> events = new ArrayList<String>();
        public int byteCount;

        /** The receipt as plain text, one line per printed line. */
        public String plainText() {
            StringBuilder sb = new StringBuilder();
            for (Line l : lines) {
                sb.append(l.text).append('\n');
            }
            return sb.toString();
        }
    }

    private EscPosDecoder() {
    }

    public static Result decode(byte[] data) {
        Result out = new Result();
        out.byteCount = data.length;

        ByteArrayOutputStream current = new ByteArrayOutputStream();
        int align = ALIGN_LEFT;
        boolean bold = false;
        boolean doubleWidth = false;
        boolean doubleHeight = false;

        int i = 0;
        while (i < data.length) {
            byte b = data[i];

            if (b == ESC) {
                int next = (i + 1 < data.length) ? (data[i + 1] & 0xFF) : -1;
                if (next == 0x40) { // ESC @ initialise
                    align = ALIGN_LEFT;
                    bold = false;
                    doubleWidth = false;
                    doubleHeight = false;
                    out.events.add("init");
                    i += 2;
                    continue;
                }
                if (next == 0x61) { // ESC a n justification
                    int n = arg(data, i + 2);
                    flush(out, current, align, bold, doubleWidth, doubleHeight);
                    align = (n <= 2) ? n : ALIGN_LEFT;
                    i += 3;
                    continue;
                }
                if (next == 0x45) { // ESC E n emphasis
                    bold = arg(data, i + 2) != 0;
                    i += 3;
                    continue;
                }
                if (next == 0x21) { // ESC ! n print mode bits
                    int n = arg(data, i + 2);
                    bold = (n & 0x08) != 0;
                    doubleHeight = (n & 0x10) != 0;
                    doubleWidth = (n & 0x20) != 0;
                    i += 3;
                    continue;
                }
                if (next == 0x64) { // ESC d n feed n lines
                    int n = arg(data, i + 2);
                    flush(out, current, align, bold, doubleWidth, doubleHeight);
                    for (int k = 0; k < n; k++) {
                        out.lines.add(new Line("", align, false, false, false));
                    }
                    i += 3;
                    continue;
                }
                if (next == 0x70) { // ESC p cash drawer pulse
                    out.events.add("cash drawer pulse");
                    i += 5;
                    continue;
                }
                if (next == 0x32) { // ESC 2 default line spacing
                    i += 2;
                    continue;
                }
                // ESC t / R / - / 3 / J and friends: one argument byte.
                i += 3;
                continue;
            }

            if (b == GS) {
                int next = (i + 1 < data.length) ? (data[i + 1] & 0xFF) : -1;
                if (next == 0x56) { // GS V cut
                    flush(out, current, align, bold, doubleWidth, doubleHeight);
                    out.events.add("paper cut");
                    i += 3;
                    continue;
                }
                if (next == 0x21) { // GS ! n character size
                    int n = arg(data, i + 2);
                    doubleWidth = ((n >> 4) & 0x0F) > 0;
                    doubleHeight = (n & 0x0F) > 0;
                    i += 3;
                    continue;
                }
                if (next == 0x76) { // GS v 0 raster bit image
                    flush(out, current, align, bold, doubleWidth, doubleHeight);
                    i += captureRaster(data, i, out, align);
                    continue;
                }
                if (next == 0x28) { // GS ( variable length command
                    int pL = arg(data, i + 3);
                    int pH = arg(data, i + 4);
                    i += 5 + (pL + pH * 256);
                    continue;
                }
                if (next == 0x6B) { // GS k barcode
                    i += skipBarcode(data, i, out);
                    continue;
                }
                // GS B / L / W / h / w / H / f: one argument byte.
                i += 3;
                continue;
            }

            if (b == FS) { // two-byte character set commands
                i += 2;
                continue;
            }

            if (b == LF) {
                flush(out, current, align, bold, doubleWidth, doubleHeight);
                i++;
                continue;
            }

            if (b == CR) {
                i++;
                continue;
            }

            if (b == HT) {
                current.write(0x20);
                i++;
                continue;
            }

            if ((b & 0xFF) == 0x7F) {
                // Loyverse emits 0x7F where the peso sign should be: its code
                // page has no glyph for it. It still OCCUPIES A COLUMN on the
                // paper, and the whole 48-column layout is measured with it in
                // place, so it becomes a space rather than being dropped.
                current.write(0x20);
                i++;
                continue;
            }

            if ((b & 0xFF) < 0x20) { // any other control byte
                i++;
                continue;
            }

            current.write(b);
            i++;
        }

        flush(out, current, align, bold, doubleWidth, doubleHeight);
        return out;
    }

    private static int arg(byte[] data, int index) {
        return (index < data.length) ? (data[index] & 0xFF) : 0;
    }

    /**
     * GS v 0 m xL xH yL yH, then the bitmap itself.
     *
     * Loyverse sends its whole receipt this way, so the block is kept rather
     * than skipped: rendering it back is the only way to see what would print.
     */
    private static int captureRaster(byte[] data, int i, Result out, int align) {
        if (i + 8 >= data.length) {
            return data.length - i;
        }
        // Layout is GS v 0 m xL xH yL yH d1..dk, so the mode byte sits at i+3
        // and the dimensions start at i+4. Reading them one byte early yields
        // plausible-looking garbage rather than an obvious failure.
        int xL = data[i + 4] & 0xFF;
        int xH = data[i + 5] & 0xFF;
        int yL = data[i + 6] & 0xFF;
        int yH = data[i + 7] & 0xFF;
        int widthBytes = xL + xH * 256;
        int height = yL + yH * 256;
        int payload = widthBytes * height;
        int available = Math.max(0, Math.min(payload, data.length - (i + 8)));

        byte[] bits = new byte[available];
        if (available > 0) {
            System.arraycopy(data, i + 8, bits, 0, available);
        }
        out.rasters.add(new Raster(widthBytes, height, bits, align));
        out.events.add("image " + (widthBytes * 8) + "x" + height + " px");

        return Math.min(8 + payload, data.length - i);
    }

    /** GS k m: either NUL-terminated or length-prefixed, depending on m. */
    private static int skipBarcode(byte[] data, int i, Result out) {
        if (i + 2 >= data.length) {
            return data.length - i;
        }
        int m = data[i + 2] & 0xFF;
        if (m >= 65) {
            int n = arg(data, i + 3);
            out.events.add("barcode " + safeString(data, i + 4, n));
            return Math.min(4 + n, data.length - i);
        }
        int j = i + 3;
        while (j < data.length && data[j] != 0) {
            j++;
        }
        out.events.add("barcode " + safeString(data, i + 3, j - (i + 3)));
        return Math.min(j - i + 1, data.length - i);
    }

    private static String safeString(byte[] data, int off, int len) {
        if (off < 0 || len <= 0 || off + len > data.length) {
            return "";
        }
        try {
            return new String(data, off, len, "ISO-8859-1").trim();
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }

    private static void flush(Result out, ByteArrayOutputStream buf, int align,
                              boolean bold, boolean dw, boolean dh) {
        byte[] raw = buf.toByteArray();
        buf.reset();
        String text;
        try {
            // ISO-8859-1 maps every byte one-to-one, so nothing is lost before
            // we know which code page the printer was told to use.
            text = new String(raw, "ISO-8859-1");
        } catch (UnsupportedEncodingException e) {
            text = new String(raw);
        }
        out.lines.add(new Line(text, align, bold, dw, dh));
    }
}
