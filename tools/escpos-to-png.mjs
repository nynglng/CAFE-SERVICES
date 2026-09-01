/**
 * Rebuild the printed image from a captured ESC/POS stream and write a PNG.
 *
 * Loyverse's graphic mode sends the receipt as a series of GS v 0 raster
 * bands. To replicate that output we first have to see it at full resolution
 * and measure it, so this reassembles the bands exactly as the printer would
 * stack them, then prints the band table used for measuring type sizes and
 * column positions.
 *
 * Usage:  node tools/escpos-to-png.mjs <capture.bin> <out.png>
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { decode, toPng } from './escpos-image.mjs';

const [, , inPath, outPath] = process.argv;
if (!inPath || !outPath) {
  console.error('usage: escpos-to-png.mjs <capture.bin> <out.png>');
  process.exit(1);
}

const page = decode(readFileSync(inPath));
console.log(`bands=${page.bands.length}  canvas=${page.width}x${page.height}`);

writeFileSync(outPath, toPng(page));
console.log(`wrote ${outPath}`);

// --- band table, for measuring type sizes and column positions ---
console.log('\n  #   y     h   widthPx  align');
let y = 0;
page.bands.forEach((b, i) => {
  console.log(
    String(i).padStart(3) + String(y).padStart(6) + String(b.height).padStart(6) +
    String(b.widthBytes * 8).padStart(9) + String(b.align).padStart(7),
  );
  y += b.height;
});
