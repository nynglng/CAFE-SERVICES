/**
 * Say what a captured print stream actually is.
 *
 * Loyverse can be set to Text or Graphic print mode, and the two produce
 * completely different streams: text arrives as characters the gateway can
 * parse, graphic arrives as GS v 0 raster bands that carry no text at all.
 * Captures do not record which mode was in force, so this works it out from
 * the bytes.
 *
 * Usage:
 *   node tools/inspect-capture.mjs captures/            list every capture
 *   node tools/inspect-capture.mjs captures/x.bin       dump one in full
 */

import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, basename } from 'node:path';

const target = process.argv[2];
if (!target) {
  console.error('usage: inspect-capture.mjs <file.bin | directory>');
  process.exit(1);
}

/** Pull the printable text out, skipping ESC/GS command payloads. */
function decode(data) {
  let out = '';
  for (let i = 0; i < data.length; ) {
    const b = data[i];

    if (b === 0x1d && data[i + 1] === 0x76 && data[i + 2] === 0x30) {
      // GS v 0 — raster band. Skip its whole payload.
      const xL = data[i + 4], xH = data[i + 5];
      const yL = data[i + 6], yH = data[i + 7];
      const width = xL + xH * 256;
      const height = yL + yH * 256;
      i += 8 + width * height;
      continue;
    }
    if (b === 0x1b || b === 0x1d) {
      // Other ESC/GS commands: most are 2-3 bytes. Skip conservatively.
      i += 3;
      continue;
    }
    if (b === 0x0a) { out += '\n'; i++; continue; }
    if (b === 0x7f) { out += ' '; i++; continue; } // peso placeholder
    if (b >= 0x20 && b < 0x7f) { out += String.fromCharCode(b); i++; continue; }
    i++;
  }
  return out;
}

/** Count raster bands and the pixels they cover. */
function raster(data) {
  let bands = 0, height = 0, width = 0;
  for (let i = 0; i < data.length; ) {
    if (data[i] === 0x1d && data[i + 1] === 0x76 && data[i + 2] === 0x30) {
      const w = data[i + 4] + data[i + 5] * 256;
      const h = data[i + 6] + data[i + 7] * 256;
      bands++;
      height += h;
      width = Math.max(width, w * 8);
      i += 8 + w * h;
      continue;
    }
    i++;
  }
  return { bands, height, width };
}

function inspect(path) {
  const data = readFileSync(path);
  const r = raster(data);
  const text = decode(data);
  const letters = (text.match(/[A-Za-z]/g) || []).length;
  const mode = r.bands > 0 ? 'GRAPHIC' : letters > 20 ? 'text' : 'other';
  return { path, size: data.length, mode, r, text, letters };
}

const isDir = statSync(target).isDirectory();

if (!isDir) {
  const i = inspect(target);
  console.log(`${basename(i.path)}  ${i.size} bytes  ${i.mode}`);
  if (i.r.bands) {
    console.log(`raster: ${i.r.bands} band(s), ${i.r.width}px wide, ${i.r.height}px tall\n`);
  }
  console.log('─'.repeat(50));
  console.log(i.text.replace(/\n{3,}/g, '\n\n'));
  console.log('─'.repeat(50));
} else {
  const files = readdirSync(target).filter((f) => f.endsWith('.bin')).sort();
  console.log(`${files.length} captures in ${target}\n`);
  console.log('FILE'.padEnd(26) + 'SIZE'.padStart(7) + '  MODE     DETAIL');
  for (const f of files) {
    const i = inspect(join(target, f));
    const detail = i.r.bands
      ? `${i.r.bands} band(s) ${i.r.width}x${i.r.height}px`
      : (i.text.trim().split('\n').find((l) => l.trim()) || '').slice(0, 34);
    console.log(
      f.replace('.bin', '').padEnd(26) +
        String(i.size).padStart(7) +
        '  ' + i.mode.padEnd(8) + ' ' + detail,
    );
  }
}
