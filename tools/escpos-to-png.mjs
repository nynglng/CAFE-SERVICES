/**
 * Rebuild the printed image from a captured ESC/POS stream and write a PNG.
 *
 * Loyverse's graphic mode sends the receipt as a series of GS v 0 raster
 * bands. To replicate that output we first have to see it at full resolution
 * and measure it, so this reassembles the bands exactly as the printer would
 * stack them.
 *
 * Usage:  node tools/escpos-to-png.mjs <capture.bin> <out.png>
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { deflateSync } from 'node:zlib';

const [, , inPath, outPath] = process.argv;
if (!inPath || !outPath) {
  console.error('usage: escpos-to-png.mjs <capture.bin> <out.png>');
  process.exit(1);
}

const data = readFileSync(inPath);

// --- collect raster bands, tracking the alignment in force for each ---
const bands = [];
let align = 0;
for (let i = 0; i < data.length; ) {
  if (data[i] === 0x1b && data[i + 1] === 0x61) {
    align = data[i + 2];
    i += 3;
    continue;
  }
  if (data[i] === 0x1d && data[i + 1] === 0x76 && data[i + 2] === 0x30) {
    const widthBytes = data[i + 4] + data[i + 5] * 256;
    const height = data[i + 6] + data[i + 7] * 256;
    const payload = widthBytes * height;
    bands.push({
      widthBytes,
      height,
      align,
      bits: data.subarray(i + 8, i + 8 + payload),
    });
    i += 8 + payload;
    continue;
  }
  i++;
}

const width = Math.max(...bands.map((b) => b.widthBytes * 8));
const height = bands.reduce((sum, b) => sum + b.height, 0);
console.log(`bands=${bands.length}  canvas=${width}x${height}`);

// --- paint into a grayscale buffer ---
const px = Buffer.alloc(width * height, 0xff);
let y0 = 0;
for (const b of bands) {
  const bandWidth = b.widthBytes * 8;
  let xOff = 0;
  if (b.align === 1) xOff = Math.floor((width - bandWidth) / 2);
  else if (b.align === 2) xOff = width - bandWidth;

  for (let y = 0; y < b.height; y++) {
    for (let xb = 0; xb < b.widthBytes; xb++) {
      const v = b.bits[y * b.widthBytes + xb];
      if (v === undefined) continue;
      for (let bit = 0; bit < 8; bit++) {
        if (v & (0x80 >> bit)) {
          const x = xOff + xb * 8 + bit;
          if (x >= 0 && x < width) px[(y0 + y) * width + x] = 0x00;
        }
      }
    }
  }
  y0 += b.height;
}

// --- minimal grayscale PNG ---
function chunk(type, body) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(body.length);
  const typed = Buffer.concat([Buffer.from(type, 'ascii'), body]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(typed) >>> 0);
  return Buffer.concat([len, typed, crc]);
}
let table = null;
function crc32(buf) {
  if (!table) {
    table = new Int32Array(256);
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      table[n] = c;
    }
  }
  let c = -1;
  for (const b of buf) c = table[(c ^ b) & 0xff] ^ (c >>> 8);
  return c ^ -1;
}

const ihdr = Buffer.alloc(13);
ihdr.writeUInt32BE(width, 0);
ihdr.writeUInt32BE(height, 4);
ihdr[8] = 8;  // bit depth
ihdr[9] = 0;  // grayscale
const raw = Buffer.alloc((width + 1) * height);
for (let y = 0; y < height; y++) {
  raw[y * (width + 1)] = 0; // filter: none
  px.copy(raw, y * (width + 1) + 1, y * width, (y + 1) * width);
}
writeFileSync(outPath, Buffer.concat([
  Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
  chunk('IHDR', ihdr),
  chunk('IDAT', deflateSync(raw)),
  chunk('IEND', Buffer.alloc(0)),
]));
console.log(`wrote ${outPath}`);

// --- band table, for measuring type sizes and column positions ---
console.log('\n  #   y     h   widthPx  align');
let y = 0;
bands.forEach((b, i) => {
  console.log(
    String(i).padStart(3) + String(y).padStart(6) + String(b.height).padStart(6) +
    String(b.widthBytes * 8).padStart(9) + String(b.align).padStart(7),
  );
  y += b.height;
});
