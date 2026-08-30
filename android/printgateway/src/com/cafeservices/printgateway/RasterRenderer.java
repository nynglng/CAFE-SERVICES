package com.cafeservices.printgateway;

import android.graphics.Bitmap;
import android.graphics.Canvas;

import java.util.List;

/**
 * Rebuilds the printed image from ESC/POS raster blocks.
 *
 * Loyverse does not send its receipt as text: it renders the whole thing to a
 * bitmap and ships it as GS v 0 blocks. That means there is nothing to read in
 * the byte stream, and the only way to see what would reach the paper is to
 * reassemble the picture.
 *
 * Blocks are stacked vertically in the order they were sent, which is how the
 * printer would feed them.
 */
public final class RasterRenderer {

    private RasterRenderer() {
    }

    /** Null when the job carried no image. */
    public static Bitmap render(List<EscPosDecoder.Raster> rasters) {
        if (rasters == null || rasters.isEmpty()) {
            return null;
        }

        int width = 0;
        int height = 0;
        for (EscPosDecoder.Raster r : rasters) {
            width = Math.max(width, r.widthPixels());
            height += r.height;
        }
        if (width <= 0 || height <= 0) {
            return null;
        }

        Bitmap canvasBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(canvasBitmap);
        canvas.drawColor(0xFFFFFFFF);

        int offsetY = 0;
        for (EscPosDecoder.Raster r : rasters) {
            Bitmap band = renderBand(r);
            if (band != null) {
                // Loyverse emits centred lines as narrow bands and relies on
                // the printer's justification to place them, so a band drawn
                // at x=0 would wrongly appear flush left.
                int x = 0;
                if (r.align == EscPosDecoder.ALIGN_CENTER) {
                    x = (width - r.widthPixels()) / 2;
                } else if (r.align == EscPosDecoder.ALIGN_RIGHT) {
                    x = width - r.widthPixels();
                }
                canvas.drawBitmap(band, Math.max(0, x), offsetY, null);
                band.recycle();
            }
            offsetY += r.height;
        }
        return canvasBitmap;
    }

    private static Bitmap renderBand(EscPosDecoder.Raster r) {
        int w = r.widthPixels();
        if (w <= 0 || r.height <= 0) {
            return null;
        }
        int[] pixels = new int[w * r.height];
        java.util.Arrays.fill(pixels, 0xFFFFFFFF);

        for (int y = 0; y < r.height; y++) {
            int rowStart = y * r.widthBytes;
            for (int xByte = 0; xByte < r.widthBytes; xByte++) {
                int index = rowStart + xByte;
                if (index >= r.bits.length) {
                    break;
                }
                int value = r.bits[index] & 0xFF;
                // Most significant bit is the leftmost pixel; a set bit is ink.
                for (int bit = 0; bit < 8; bit++) {
                    if ((value & (0x80 >> bit)) != 0) {
                        pixels[y * w + xByte * 8 + bit] = 0xFF000000;
                    }
                }
            }
        }

        Bitmap bitmap = Bitmap.createBitmap(w, r.height, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, w, 0, 0, w, r.height);
        return bitmap;
    }
}
