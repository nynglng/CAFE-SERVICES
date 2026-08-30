package com.cafeservices.printgateway;

import android.graphics.Bitmap;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** One print the POS sent us, kept as both raw bytes and decoded text. */
public final class PrintJob {

    public final long receivedAt;
    public final String source;
    /** Which of the device's addresses the POS printed to. */
    public final String dialled;
    public final byte[] raw;
    public final EscPosDecoder.Result decoded;

    private Bitmap rendered;
    private boolean renderAttempted;

    /** The printed image, or null if this job was plain text. */
    public Bitmap bitmap() {
        if (!renderAttempted) {
            renderAttempted = true;
            rendered = RasterRenderer.render(decoded.rasters);
        }
        return rendered;
    }

    public boolean hasImage() {
        return !decoded.rasters.isEmpty();
    }

    public PrintJob(String source, String dialled, byte[] raw) {
        this.receivedAt = System.currentTimeMillis();
        this.source = source;
        this.dialled = dialled;
        this.raw = raw;
        this.decoded = EscPosDecoder.decode(raw);
    }

    /** The role configured for the address this job was sent to. */
    public PrinterRole role() {
        return PrinterRole.forAddress(dialled);
    }

    public String time() {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(receivedAt));
    }

    /**
     * How the receipt would look on paper, at the given character width, with
     * alignment applied. This is the stand-in for the printer we do not have
     * yet: what you read here is what the paper would say.
     */
    public String preview(int width) {
        StringBuilder sb = new StringBuilder();
        for (EscPosDecoder.Line line : decoded.lines) {
            String text = line.text;
            // A double-width character occupies two cells on paper, so half as
            // many fit per line, but it still spans the full width when centred.
            int cell = line.doubleWidth ? 2 : 1;
            int usable = width / cell;
            for (String part : wrap(text, usable)) {
                sb.append(pad(part, line.align, width, cell)).append('\n');
            }
        }
        return sb.toString();
    }

    private static List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<String>();
        if (text.length() == 0) {
            out.add("");
            return out;
        }
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + width, text.length());
            out.add(text.substring(start, end));
            start = end;
        }
        return out;
    }

    private static String pad(String text, int align, int width, int cell) {
        int occupied = text.length() * cell;
        if (occupied >= width) {
            return text;
        }
        int slack = width - occupied;
        if (align == EscPosDecoder.ALIGN_CENTER) {
            int left = slack / 2;
            return repeat(left) + text;
        }
        if (align == EscPosDecoder.ALIGN_RIGHT) {
            return repeat(slack) + text;
        }
        return text;
    }

    private static String repeat(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(' ');
        }
        return sb.toString();
    }
}
