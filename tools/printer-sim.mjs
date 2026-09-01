/**
 * A thermal printer, on the laptop, for when the real one is not here.
 *
 * Shows what would have come out of the printer. Two things can deliver a
 * page, and the same print usually arrives by both:
 *
 * bluetooth - bt-printer.ps1 makes the laptop answer as a Bluetooth printer,
 *   so the phone looks up the standard serial-port service, dials RFCOMM and
 *   writes, exactly as it would to the JK-5802H. This is the real path.
 *
 * phone - the gateway saves the bytes it means to send beside every job,
 *   whether or not a printer is paired, so the paper does not have to exist
 *   for us to see what would be on it. Pulled over adb.
 *
 * A page that arrives both ways is shown once, and says so. That is not just
 * tidiness: the two are the same page only if the Bluetooth link delivered
 * exactly what the gateway encoded, so comparing them is a real check, and a
 * page that appears by only one route after both were running is a finding
 * rather than a display problem.
 *
 * Usage:  node tools/printer-sim.mjs [--port 8080] [--every 1500]
 */
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { createServer } from 'node:http';
import {
  existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync,
} from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { decode, events, toPng } from './escpos-image.mjs';

const ROOT = dirname(dirname(fileURLToPath(import.meta.url)));
const OUT = join(ROOT, 'captures', 'sim');
const PHONE = join(OUT, 'phone');
const BT = join(OUT, 'bt');
const JOBS = '/sdcard/Android/data/com.cafeservices.printgateway/files/jobs';

/** Where pages come from, and what to call each on the card. */
const SOURCES = [
  { dir: PHONE, label: 'phone' },
  { dir: BT, label: 'bluetooth' },
];

const args = process.argv.slice(2);
const flag = (name, fallback) => {
  const i = args.indexOf(name);
  return i >= 0 && args[i + 1] ? Number(args[i + 1]) : fallback;
};
const PORT = flag('--port', 8080);
const EVERY_MS = flag('--every', 1500);

/** adb is rarely on PATH on Windows, so look where the SDK puts it. */
function findAdb() {
  const candidates = [
    process.env.ADB,
    join(process.env.LOCALAPPDATA || '', 'Android', 'Sdk', 'platform-tools', 'adb.exe'),
    join(process.env.ANDROID_HOME || '', 'platform-tools', 'adb.exe'),
    'adb',
  ].filter(Boolean);
  for (const candidate of candidates) {
    if (!spawnSync(candidate, ['version'], { encoding: 'utf8' }).error) return candidate;
  }
  return null;
}

const ADB = findAdb();
if (!ADB) {
  console.error('adb not found. Set ADB=<path to adb.exe> and try again.');
  process.exit(1);
}

const adb = (...a) => spawnSync(ADB, a, { encoding: 'buffer', maxBuffer: 64 << 20 });
const adbText = (...a) => {
  const r = adb(...a);
  return r.stdout ? r.stdout.toString('utf8') : '';
};

/** One entry per distinct page, oldest first. */
const pages = [];
const byContent = new Map();
const seen = new Set();
const pulled = new Set();

function phoneAttached() {
  return /\tdevice\s*$/m.test(adbText('devices'));
}

/** Copy anything new off the phone into its own folder. */
function pullFromPhone() {
  const listing = adbText('shell', 'ls', '-1', JOBS);
  if (/No such file|error:/.test(listing)) return;
  for (const raw of listing.split(/\r?\n/)) {
    const name = raw.trim();
    if (!name.endsWith('-print.bin') || pulled.has(name)) continue;
    if (existsSync(join(PHONE, name))
        || adb('pull', JOBS + '/' + name, join(PHONE, name)).status === 0) {
      pulled.add(name);
    }
  }
}

function take(dir, label, name) {
  const file = join(dir, name);
  if (!existsSync(file)) return false;
  const bytes = readFileSync(file);
  if (bytes.length === 0) return false;

  // Identical bytes are the same page however many ways it reached us. The
  // phone saves what it is about to send and the printer records what it
  // received, so one print legitimately produces two files.
  const hash = createHash('md5').update(bytes).digest('hex');
  const already = byContent.get(hash);
  if (already) {
    if (!already.via.includes(label)) {
      already.via.push(label);
      console.log('  also arrived via ' + label + ': ' + name);
    }
    return true;
  }

  const page = decode(bytes);
  // A stream with no bands is either a pass-through we could not redraw or a
  // file caught mid-write. Either way there is nothing to show, and leaving it
  // unseen means the next poll tries again.
  if (page.bands.length === 0) return false;

  const png = name.replace(/\.bin$/, '.png');
  writeFileSync(join(OUT, png), toPng(page));

  const what = events(bytes);
  const entry = {
    name,
    png,
    hash,
    via: [label],
    bytes: bytes.length,
    width: page.width,
    height: page.height,
    events: what,
    at: new Date().toISOString(),
  };
  pages.push(entry);
  byContent.set(hash, entry);

  const mm = (page.height / 203) * 25.4;
  console.log(
    'printed ' + name + '  ' + page.width + 'x' + page.height + 'px  ' +
      mm.toFixed(0) + 'mm of paper  ' + (bytes.length / 1024).toFixed(1) + ' KB  [' +
      what.join(', ') + ']  via ' + label,
  );
  return true;
}

function poll() {
  if (phoneAttached()) pullFromPhone();
  for (const { dir, label } of SOURCES) {
    if (!existsSync(dir)) continue;
    const names = readdirSync(dir).filter((n) => n.endsWith('-print.bin')).sort();
    for (const name of names) {
      const key = label + ':' + name;
      if (seen.has(key)) continue;
      if (take(dir, label, name)) seen.add(key);
    }
  }
}

/**
 * What the page says about where jobs can come from.
 *
 * Only the usb half can be checked. Whether the Bluetooth printer is running
 * is not knowable from here - a job simply appears in its folder - so it is
 * reported by its results rather than claimed.
 */
function deviceLine() {
  const usb = phoneAttached() ? 'phone on usb' : 'no phone on usb';
  return usb + ' · ' + pages.length + ' page(s)';
}

// --- the paper roll ---

const PAGE = [
  '<!doctype html><meta charset="utf-8">',
  '<title>Printer simulator</title>',
  '<style>',
  ':root{color-scheme:light dark;--ink:#111;--bg:#f4f4f2;--card:#fff;--dim:#666}',
  '@media (prefers-color-scheme:dark){',
  ':root{--ink:#eee;--bg:#141414;--card:#1e1e1e;--dim:#999}}',
  'body{margin:0;background:var(--bg);color:var(--ink);',
  'font:13px/1.5 ui-monospace,Menlo,Consolas,monospace}',
  'header{padding:14px 18px;border-bottom:1px solid #8884;position:sticky;',
  'top:0;background:var(--bg)}',
  'h1{font-size:14px;margin:0 0 2px;letter-spacing:.04em}',
  '.dim{color:var(--dim)}',
  '#roll{display:flex;flex-wrap:wrap;gap:18px;padding:18px;align-items:flex-start}',
  'figure{margin:0;background:var(--card);padding:10px;border-radius:6px;',
  'box-shadow:0 1px 4px #0003}',
  'img{display:block;width:384px;image-rendering:pixelated;background:#fff}',
  'figcaption{color:var(--dim);padding-top:8px;font-size:11px}',
  '.via{color:var(--ink);opacity:.75}',
  '.empty{padding:18px;color:var(--dim)}',
  '</style>',
  '<header><h1>PRINTER SIMULATOR &mdash; 58mm</h1>',
  '<div class="dim" id="status">waiting&hellip;</div></header>',
  '<div id="roll"></div>',
  '<p class="empty" id="empty">Nothing yet. Print a sale from Loyverse to',
  ' 127.0.0.1:9100 on the phone.</p>',
  '<script>',
  'let count=-1,vias="";',
  'async function tick(){',
  ' try{',
  '  const d=await (await fetch("/feed")).json();',
  '  document.getElementById("status").textContent=d.device;',
  '  const stamp=d.pages.map(p=>p.via.join("+")).join("|");',
  '  if(d.pages.length!==count||stamp!==vias){',
  '   count=d.pages.length;vias=stamp;',
  '   document.getElementById("empty").hidden=count>0;',
  '   document.getElementById("roll").innerHTML=d.pages.slice().reverse().map(p=>',
  '    "<figure><img src=\\"/png/"+encodeURIComponent(p.png)+"\\">"+',
  '    "<figcaption>"+p.name+"<br>"+p.width+"\\u00d7"+p.height+"px \\u00b7 "+',
  '    (p.bytes/1024).toFixed(1)+" KB \\u00b7 "+((p.height/203)*25.4).toFixed(0)+"mm<br>"+',
  '    (p.events.join(", ")||"no control codes")+',
  '    "<br><span class=via>via "+p.via.join(" + ")+"</span>"+',
  '    "</figcaption></figure>").join("");',
  '  }',
  ' }catch(e){',
  '  document.getElementById("status").textContent="simulator stopped";',
  ' }',
  '}',
  'tick();setInterval(tick,1500);',
  '</script>',
].join('\n');

for (const dir of [OUT, PHONE, BT]) {
  if (!existsSync(dir)) mkdirSync(dir, { recursive: true });
}

createServer((req, res) => {
  if (req.url === '/' || req.url.startsWith('/?')) {
    res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
    res.end(PAGE);
    return;
  }
  if (req.url === '/feed') {
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ device: deviceLine(), pages }));
    return;
  }
  if (req.url.startsWith('/png/')) {
    const name = decodeURIComponent(req.url.slice(5).split('?')[0]);
    const file = join(OUT, name);
    if (name.includes('..') || !existsSync(file)) {
      res.writeHead(404).end();
      return;
    }
    res.writeHead(200, { 'content-type': 'image/png' });
    res.end(readFileSync(file));
    return;
  }
  res.writeHead(404).end();
}).listen(PORT, () => {
  console.log('printer simulator on http://localhost:' + PORT);
  console.log(deviceLine());
  console.log('phone jobs  -> ' + PHONE);
  console.log('bluetooth   -> ' + BT);
  console.log('a print that arrives both ways is shown once\n');
});

poll();
setInterval(poll, EVERY_MS);
