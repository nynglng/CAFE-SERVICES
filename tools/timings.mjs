/**
 * Read the gateway's own timing lines off the phone and lay them out.
 *
 * Every job logs one line naming each phase of the wait, and the printer logs
 * whether it reused the Bluetooth link or reopened it. This collects both,
 * puts one sale per row, and gives the median of each column - the median
 * rather than the mean because a single dropped link or lapsed session
 * produces an outlier that would otherwise swallow the picture.
 *
 * The phases, in the order the cashier experiences them:
 *
 *   wait     Loyverse accepted the connection, then took this long to send.
 *            Not ours; it is the POS deciding to talk.
 *   read     receiving the job, ending at the cut.
 *   rawsave  writing the untouched capture to disk.
 *   issue    getting real voucher codes out of Omada. The customer's wait.
 *   tidy     deleting spent groups and counting the site. Housekeeping that
 *            currently happens before the paper, and need not.
 *   draw     rendering the receipt and voucher.
 *   png      saving the debug replica.
 *   encode   turning the bitmap into ESC/POS.
 *   binsave  writing what will be sent to disk.
 *   send     the Bluetooth write, including a connect if the link had gone.
 *   counter  first byte to last - what someone standing there experiences.
 *
 * Usage:
 *   node tools/timings.mjs --clear    before a run, to start from nothing
 *   node tools/timings.mjs            after it, to read the result
 */
import { spawnSync } from 'node:child_process';
import { join } from 'node:path';

const args = process.argv.slice(2);

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

if (args.includes('--clear')) {
  spawnSync(ADB, ['logcat', '-c'], { encoding: 'utf8' });
  console.log('log cleared. Do your sales, then run this again without --clear.');
  process.exit(0);
}

const log = spawnSync(ADB, ['logcat', '-d', '-s', 'PrintGateway:I'], {
  encoding: 'utf8',
  maxBuffer: 64 << 20,
}).stdout || '';

// --- one row per sale ---

const ORDER = ['wait', 'read', 'rawsave', 'issue', 'tidy', 'draw', 'png',
  'encode', 'binsave', 'send', 'counter'];

const rows = [];
for (const line of log.split(/\r?\n/)) {
  const m = /timing:\s*(.+)$/.exec(line);
  if (!m) continue;
  const row = {};
  for (const part of m[1].split(',')) {
    const f = /^\s*([a-z>-]+)\s+(\d+)ms\s*$/.exec(part);
    if (f) row[f[1]] = Number(f[2]);
  }
  if (Object.keys(row).length) rows.push(row);
}

if (rows.length === 0) {
  console.log('No timing lines in the log.\n');
  console.log('Either no sale has been printed on this build, or the log has');
  console.log('rotated - the ring buffer is small and fills within minutes.');
  console.log('Run with --clear, print a sale, then run this again.');
  process.exit(0);
}

const median = (xs) => {
  const s = xs.slice().sort((a, b) => a - b);
  const mid = s.length >> 1;
  return s.length % 2 ? s[mid] : Math.round((s[mid - 1] + s[mid]) / 2);
};

const columns = ORDER.filter((c) => rows.some((r) => r[c] !== undefined));
const width = (c) => Math.max(c.length, 5, ...rows.map((r) => String(r[c] ?? '-').length));

const head = ['  #', ...columns.map((c) => c.padStart(width(c)))].join('  ');
console.log('\n' + head);
console.log('-'.repeat(head.length));
rows.forEach((r, i) => {
  console.log([
    String(i + 1).padStart(3),
    ...columns.map((c) => String(r[c] ?? '-').padStart(width(c))),
  ].join('  '));
});
console.log('-'.repeat(head.length));
console.log(['med', ...columns.map((c) =>
  String(median(rows.filter((r) => r[c] !== undefined).map((r) => r[c])))
    .padStart(width(c)))].join('  '));

// --- what the wait is actually made of ---

const med = {};
for (const c of columns) {
  med[c] = median(rows.filter((r) => r[c] !== undefined).map((r) => r[c]));
}
const counter = med.counter ?? 0;
console.log('\n' + rows.length + ' sale(s). Median at the counter: ' + counter + 'ms\n');
if (counter > 0) {
  for (const c of columns) {
    if (c === 'counter' || c === 'wait') continue;
    const share = Math.round((med[c] / counter) * 100);
    const bar = '#'.repeat(Math.min(40, Math.round(share / 2.5)));
    console.log('  ' + c.padEnd(8) + String(med[c]).padStart(6) + 'ms  ' +
      String(share).padStart(3) + '%  ' + bar);
  }
}

// --- the two things the phases alone will not tell you ---

const omada = [];
for (const line of log.split(/\r?\n/)) {
  const m = /omada (GET|POST|DELETE) (\S+) (\d+)ms/.exec(line);
  if (m) omada.push({ method: m[1], what: m[2], ms: Number(m[3]) });
}
if (omada.length) {
  console.log('\nOmada: ' + omada.length + ' call(s), median ' +
    median(omada.map((o) => o.ms)) + 'ms per round trip, slowest ' +
    Math.max(...omada.map((o) => o.ms)) + 'ms');
  console.log('  ' + (omada.length / rows.length).toFixed(1) + ' calls per sale');
}

const links = [];
for (const line of log.split(/\r?\n/)) {
  const m = /link (reused|opened in (\d+)ms), sent in (\d+)ms/.exec(line);
  if (m) links.push({ reused: m[1] === 'reused', open: Number(m[2] || 0), sent: Number(m[3]) });
}
if (links.length) {
  const reused = links.filter((l) => l.reused).length;
  console.log('\nBluetooth: ' + reused + ' of ' + links.length +
    ' print(s) reused the open link');
  const opened = links.filter((l) => !l.reused).map((l) => l.open);
  if (opened.length) {
    console.log('  connecting cost a median of ' + median(opened) + 'ms when it happened');
  }
  console.log('  writing the page took a median of ' +
    median(links.map((l) => l.sent)) + 'ms');
}
console.log('');
