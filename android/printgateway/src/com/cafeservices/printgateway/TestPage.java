package com.cafeservices.printgateway;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;

/**
 * The page behind Print test.
 *
 * Loyverse's own test says only that the printer answered. This one is meant
 * to answer the questions that actually go wrong when a gateway lands at a new
 * cafe: is the paper the width we think it is, does the cut happen, does the
 * type land where it should. So it prints a rule the full width of the paper -
 * if that rule runs off the edge or stops short, the paper setting is wrong
 * and everything else will be too.
 */
public final class TestPage {

    private TestPage() {
    }

    public static Bitmap render(PaperProfile paper) {
        Paint bold = paint(ReceiptRenderer.TOTAL_SIZE, true);
        Paint body = paint(ReceiptRenderer.BODY_SIZE, false);

        String[] lines = {
                "printer: " + settingOrDash(Settings.printerName()),
                "paper: " + paper.name + "  (" + paper.paperWidth + " dots)",
                "print width: " + Settings.printWidthMm() + " mm at "
                        + Settings.dpi() + " dpi",
                "cutter: " + settingOrDash(Settings.cutterCommands()),
                "drawer: " + settingOrDash(Settings.drawerCommands()),
                "wi-fi: " + Settings.ssid(),
                "site: " + settingOrDash(Settings.siteName()),
        };

        int pitch = ReceiptRenderer.PITCH_WRAPPED;
        int height = pitch * (lines.length + 5);
        Bitmap bitmap = Bitmap.createBitmap(paper.paperWidth, height,
                Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);

        int y = pitch;
        String title = "GATEWAY TEST";
        canvas.drawText(title, (paper.paperWidth - bold.measureText(title)) / 2f,
                y + bold.getTextSize(), bold);
        y += pitch + 12;

        // The width check: a rule from margin to margin. If this does not run
        // edge to edge on the paper, the paper setting is wrong.
        Paint rule = new Paint();
        rule.setColor(Color.BLACK);
        canvas.drawRect(2, y, paper.marginRight, y + 3, rule);
        y += pitch;

        for (String line : lines) {
            canvas.drawText(line, 2, y + body.getTextSize(), body);
            y += pitch;
        }

        canvas.drawRect(2, y, paper.marginRight, y + 3, rule);
        return bitmap;
    }

    private static String settingOrDash(String value) {
        return value == null || value.length() == 0 ? "-" : value;
    }

    private static Paint paint(float size, boolean bold) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.BLACK);
        p.setTextSize(size);
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF,
                bold ? Typeface.BOLD : Typeface.NORMAL));
        return p;
    }
}
