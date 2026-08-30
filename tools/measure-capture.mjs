/**
 * Measure a graphic-mode capture so the renderer can be calibrated against it.
 *
 * Loyverse's graphic mode is the ground truth for what the paper should look
 * like: it sends the finished picture. Reassembling it and measuring where the
 * ink actually falls gives real numbers for margins, line pitch and type
 * height, instead of numbers picked by eye.
 *
 * Usage:  node tools/measure-capture.mjs captures/x.bin
 */

import { readFileSync } from 'node:fs';

const path = process.argv[2];
if (!path) {
  console.error('usage: measure-capture.mjs <capture.bin>');
  process.exit(1);
}
const data = readFileSync(path);

// --- reassemble the raster bands into one bitmap ---
const rows = [];
let widthPx = 0;
for (let i = 0; i < data.length; ) {
  if (data[i] === 0x1d && data[i + 1] === 0x76 && data[i + 2] === 0x30) {
    const wBytes = data[i + 4] + data[i + 5] * 256;
    const h = data[i + 6] + data[i + 7] * 256;
    const start = i + 8;
    widthPx = Math.max(widthPx, wBytes * 8);
    for (let y = 0; y < h; y++) {
      const row = new Uint8Array(wBytes * 8);
      for (let bx = 0; bx < wBytes; bx++) {
        const byte = data[start + y * wBytes + bx];
        for (let bit = 0; bit < 8; bit++) {
          row[bx * 8 + bit] = (byte >> (7 - bit)) & 1;
        }
      }
      rows.push(row);
    }
    i = start + wBytes * h;
    continue;
  }
  i++;
}

console.log(`${path}`);
console.log(`bitmap: ${widthPx} x ${rows.length}px\n`);

// --- per-row ink extents ---
let globalLeft = Infinity, globalRight = -1;
const inked = rows.map((row) => {
  let left = -1, right = -1;
  for (let x = 0; x < row.length; x++) {
    if (row[x]) {
      if (left === -1) left = x;
      right = x;
    }
  }
  if (left !== -1) {
    globalLeft = Math.min(globalLeft, left);
    globalRight = Math.max(globalRight, right);
  }
  return { left, right, any: left !== -1 };
});

console.log(`ink spans x = ${globalLeft} .. ${globalRight}`);
console.log(`  left margin  ${globalLeft}px`);
console.log(`  right margin ${widthPx - 1 - globalRight}px\n`);

// --- group consecutive inked rows into text lines ---
const lines = [];
let run = null;
for (let y = 0; y < inked.length; y++) {
  if (inked[y].any) {
    if (!run) run = { top: y, bottom: y, left: inked[y].left, right: inked[y].right };
    else {
      run.bottom = y;
      run.left = Math.min(run.left, inked[y].left);
      run.right = Math.max(run.right, inked[y].right);
    }
  } else if (run) {
    lines.push(run);
    run = null;
  }
}
if (run) lines.push(run);

console.log(`${lines.length} inked bands (text lines and rules)\n`);
console.log('  #   top  height   x-range      baseline-to-baseline');
let prevTop = null;
for (const [i, l] of lines.entries()) {
  const h = l.bottom - l.top + 1;
  const pitch = prevTop === null ? '' : String(l.top - prevTop);
  console.log(
    `  ${String(i).padStart(2)}  ${String(l.top).padStart(4)}  ${String(h).padStart(5)}   ` +
      `${String(l.left).padStart(3)}..${String(l.right).padEnd(3)}   ${pitch.padStart(4)}`,
  );
  prevTop = l.top;
}

// --- common pitches, which is what the renderer needs ---
const pitches = {};
for (let i = 1; i < lines.length; i++) {
  const p = lines[i].top - lines[i - 1].top;
  pitches[p] = (pitches[p] || 0) + 1;
}
console.log('\nmost common line pitches:');
Object.entries(pitches)
  .sort((a, b) => b[1] - a[1])
  .slice(0, 8)
  .forEach(([p, n]) => console.log(`  ${String(p).padStart(3)}px  x${n}`));

const heights = {};
for (const l of lines) {
  const h = l.bottom - l.top + 1;
  heights[h] = (heights[h] || 0) + 1;
}
console.log('\nmost common band heights (type height):');
Object.entries(heights)
  .sort((a, b) => b[1] - a[1])
  .slice(0, 8)
  .forEach(([h, n]) => console.log(`  ${String(h).padStart(3)}px  x${n}`));
