// Compare how much ink Loyverse burns against how much we would, so the
// 1-bit threshold can be chosen to match rather than guessed at.
import { readFileSync } from 'node:fs';
import { inflateSync } from 'node:zlib';

// --- Loyverse: count set bits in its raster bands ---
function loyverseInk(path) {
  const d = readFileSync(path);
  let ink = 0, rows = 0, width = 0;
  for (let i = 0; i < d.length; ) {
    if (d[i] === 0x1d && d[i + 1] === 0x76 && d[i + 2] === 0x30) {
      const wB = d[i + 4] + d[i + 5] * 256;
      const h = d[i + 6] + d[i + 7] * 256;
      const start = i + 8;
      width = Math.max(width, wB * 8);
      for (let k = 0; k < wB * h; k++) {
        let byte = d[start + k];
        while (byte) { ink += byte & 1; byte >>= 1; }
      }
      rows += h;
      i = start + wB * h;
      continue;
    }
    i++;
  }
  return { ink, rows, width };
}

// --- our replica: decode the PNG and count at several thresholds ---
function pngPixels(path) {
  const d = readFileSync(path);
  const w = d.readUInt32BE(16), h = d.readUInt32BE(20);
  const depth = d[24], colour = d[25];
  if (depth !== 8) throw new Error('expected 8-bit png, got depth ' + depth);
  const channels = { 0: 1, 2: 3, 4: 2, 6: 4 }[colour];
  if (!channels) throw new Error('unsupported colour type ' + colour);

  const chunks = [];
  let p = 8;
  while (p < d.length) {
    const len = d.readUInt32BE(p);
    const type = d.toString('ascii', p + 4, p + 8);
    if (type === 'IDAT') chunks.push(d.subarray(p + 8, p + 8 + len));
    p += 12 + len;
  }
  const raw = inflateSync(Buffer.concat(chunks));

  const stride = w * channels;
  const out = new Uint8Array(w * h); // luminance
  const prev = new Uint8Array(stride);
  const cur = new Uint8Array(stride);
  let off = 0;
  for (let y = 0; y < h; y++) {
    const filter = raw[off++];
    for (let x = 0; x < stride; x++) {
      const rawByte = raw[off + x];
      const a = x >= channels ? cur[x - channels] : 0;
      const b = prev[x];
      const c = x >= channels ? prev[x - channels] : 0;
      let v;
      if (filter === 0) v = rawByte;
      else if (filter === 1) v = rawByte + a;
      else if (filter === 2) v = rawByte + b;
      else if (filter === 3) v = rawByte + ((a + b) >> 1);
      else {
        const pa = Math.abs(b - c), pb = Math.abs(a - c), pc = Math.abs(a + b - 2 * c);
        v = rawByte + (pa <= pb && pa <= pc ? a : pb <= pc ? b : c);
      }
      cur[x] = v & 0xFF;
    }
    off += stride;
    for (let x = 0; x < w; x++) {
      const i = x * channels;
      const r = cur[i], g = channels >= 3 ? cur[i + 1] : cur[i], bb = channels >= 3 ? cur[i + 2] : cur[i];
      out[y * w + x] = (r * 77 + g * 151 + bb * 28) >> 8;
    }
    prev.set(cur);
  }
  return { w, h, lum: out };
}

const loy = loyverseInk(process.argv[2]);
const ours = pngPixels(process.argv[3]);

console.log(`Loyverse : ${loy.width}px wide, ${loy.rows} rows, ${loy.ink} ink dots`);
console.log(`  density: ${(loy.ink / (loy.width * loy.rows) * 100).toFixed(2)}% of the page\n`);
console.log(`Ours     : ${ours.w}x${ours.h}`);

for (const t of [128, 145, 160, 176, 190, 205]) {
  let ink = 0;
  for (let i = 0; i < ours.lum.length; i++) if (ours.lum[i] < t) ink++;
  const density = ink / (ours.w * ours.h) * 100;
  const ratio = (ink / ours.h) / (loy.ink / loy.rows);
  console.log(
    `  threshold ${String(t).padStart(3)}: ${String(ink).padStart(7)} dots` +
    `  ${density.toFixed(2)}%  ink-per-row vs Loyverse ${ratio.toFixed(3)}x` +
    (Math.abs(ratio - 1) < 0.06 ? '   <-- matches' : '')
  );
}
