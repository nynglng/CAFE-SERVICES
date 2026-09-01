/**
 * Turning a captured ESC/POS stream back into the picture it would print.
 *
 * Shared by the one-shot converter and the printer simulator, because both
 * need exactly the same answer: what would have come out of the printer. Any
 * drift between two copies of this would be invisible and would quietly
 * invalidate every comparison we have made against Loyverse's own output.
 */
import { deflateSync } from 'node:zlib';

/**
 * Stack the raster bands of a stream the way the printer would.
 *
 * Graphic mode sends the page as a series of GS v 0 bands, each with the
 * alignment in force when it was sent - so alignment has to be tracked while
 * scanning, not read off the end. Everything else in the stream (init, feeds,
 * the drawer pulse, the cut) moves no ink and is skipped.
 *
 * @returns {{width: number, height: number, px: Buffer, bands: object[]}}
 *   px is one grayscale byte per pixel, 0xff paper and 0x00 ink.
 */
export function decode(data) {
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

  if (bands.length === 0) {
    return { width: 0, height: 0, px: Buffer.alloc(0), bands };
  }

  const width = Math.max(...bands.map((b) => b.widthBytes * 8));
  const height = bands.reduce((sum, b) => sum + b.height, 0);
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

  return { width, height, px, bands };
}

/** Everything in the stream that is not ink: what the printer would do. */
export function events(data) {
  const out = [];
  for (let i = 0; i < data.length; i++) {
    if (data[i] === 0x1b && data[i + 1] === 0x40) out.push('initialise');
    else if (data[i] === 0x1b && data[i + 1] === 0x70) out.push('open cash drawer');
    else if (data[i] === 0x1d && data[i + 1] === 0x56) out.push('cut');
  }
  return out;
}

// --- a minimal grayscale PNG, so the tools stay dependency free ---

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

function chunk(type, body) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(body.length);
  const typed = Buffer.concat([Buffer.from(type, 'ascii'), body]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(typed) >>> 0);
  return Buffer.concat([len, typed, crc]);
}

export function toPng({ width, height, px }) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 0; // grayscale
  const raw = Buffer.alloc((width + 1) * height);
  for (let y = 0; y < height; y++) {
    raw[y * (width + 1)] = 0; // filter: none
    px.copy(raw, y * (width + 1) + 1, y * width, (y + 1) * width);
  }
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', deflateSync(raw)),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}
