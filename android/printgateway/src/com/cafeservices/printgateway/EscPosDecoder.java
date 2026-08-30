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

    public static final class Result {
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
                    int consumed = skipRaster(data, i);
                    out.events.add("image or logo, " + consumed + " bytes");
                    i += consumed;
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

    /** GS v 0 m xL xH yL yH, then the bitmap itself. */
    private static int skipRaster(byte[] data, int i) {
        if (i + 7 >= data.length) {
            return data.length - i;
        }
        int xL = data[i + 3] & 0xFF;
        int xH = data[i + 4] & 0xFF;
        int yL = data[i + 5] & 0xFF;
        int yH = data[i + 6] & 0xFF;
        int bytes = (xL + xH * 256) * (yL + yH * 256);
        return Math.min(7 + bytes, data.length - i);
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
