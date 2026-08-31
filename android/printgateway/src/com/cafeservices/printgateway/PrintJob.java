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
    private Bitmap replicaBitmap;
    private boolean replicaAttempted;
    private List<String> issuedCodes;

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

    /**
     * The receipt redrawn the way Loyverse's graphic mode would have drawn it.
     *
     * Loyverse sends us text, but the paper is meant to carry a picture, so
     * this - not the incoming stream - is what the printer will eventually be
     * handed, and therefore what the screen should be showing. Null when the
     * job is already an image, carries no items, or is a voucher-only sale
     * with no receipt to print.
     */
    public Bitmap replica() {
        if (replicaAttempted) {
            return replicaBitmap;
        }
        replicaAttempted = true;
        if (hasImage()) {
            return null;
        }
        ReceiptParser.Receipt receipt = ReceiptParser.parse(decoded);
        if (receipt.items.isEmpty()) {
            return null;
        }
        SalePlan plan = SalePlan.from(receipt);
        Bitmap top = ReceiptRenderer.render(plan, receipt,
                MainActivity.storeHeader(receipt));
        Bitmap bottom = null;
        if (plan.printVoucher) {
            bottom = hasIssuedCodes()
                    ? VoucherRenderer.render(issuedCodes, Settings.ssid(),
                            Settings.accessHours(), Settings.expiryHours())
                    : VoucherRenderer.renderUnavailable();
        }
        replicaBitmap = stack(top, bottom);
        return replicaBitmap;
    }

    /** Codes Omada issued for this sale, set before the replica is drawn. */
    public void setIssuedCodes(List<String> codes) {
        this.issuedCodes = codes;
        forgetReplica();
    }

    /** The codes on this sale, empty when none were issued. */
    public List<String> issuedCodes() {
        return issuedCodes == null ? new ArrayList<String>() : issuedCodes;
    }

    /** False when Omada could not be reached and the slip shows stand-ins. */
    public boolean hasIssuedCodes() {
        return issuedCodes != null && !issuedCodes.isEmpty();
    }

    /** Drop the drawn copy so a settings change shows up on an old job. */
    public void forgetReplica() {
        replicaAttempted = false;
        replicaBitmap = null;
    }

    /** One above the other on the same strip of paper. */
    private static Bitmap stack(Bitmap top, Bitmap bottom) {
        if (top == null) {
            return bottom;
        }
        if (bottom == null) {
            return top;
        }
        int width = Math.max(top.getWidth(), bottom.getWidth());
        Bitmap out = Bitmap.createBitmap(width,
                top.getHeight() + bottom.getHeight(), Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(out);
        canvas.drawColor(android.graphics.Color.WHITE);
        canvas.drawBitmap(top, 0, 0, null);
        canvas.drawBitmap(bottom, 0, top.getHeight(), null);
        return out;
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

}
