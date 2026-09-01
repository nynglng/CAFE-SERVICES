/**
 * Watch the gateway go idle, without waking it.
 *
 * The freeze that stops Loyverse printing is invisible from inside the app -
 * a frozen process cannot report that it is frozen - and it is undone by the
 * act of looking, because opening the app or connecting to its port is exactly
 * what thaws it. So everything here reads the phone from outside: the kernel's
 * own view of the process, and the log buffer, neither of which touches the
 * app.
 *
 * Three columns, and between them they say everything:
 *
 *   frozen    the cgroup the process is in. "/" is healthy; "/frozen" means
 *             it cannot execute a single instruction, and any print arriving
 *             now will sit unread until something thaws it.
 *   silent    seconds since the gateway last logged its 45-second heartbeat.
 *             Anything past about 60 means it has stopped running.
 *   queued    connections accepted by the kernel that the app has not picked
 *             up, and bytes waiting in their buffers. Non-zero here is a
 *             receipt Loyverse has already sent and nobody has read.
 *
 * Usage:  node tools/watch-gateway.mjs [--every 30]
 */
import { spawnSync } from 'node:child_process';
import { join } from 'node:path';

const args = process.argv.slice(2);
const everyIndex = args.indexOf('--every');
const EVERY_MS = (everyIndex >= 0 && args[everyIndex + 1]
  ? Number(args[everyIndex + 1]) : 30) * 1000;

const PKG = 'com.cafeservices.printgateway';
/** Port 9100, as the kernel writes it in /proc/net/tcp. */
const PORT_HEX = '238C';

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

const sh = (cmd) => {
  const r = spawnSync(ADB, ['shell', cmd], { encoding: 'utf8', maxBuffer: 16 << 20 });
  return (r.stdout || '').trim();
};

function pid() {
  const out = sh('ps -A | grep ' + PKG);
  const m = /^\S+\s+(\d+)/m.exec(out);
  return m ? m[1] : null;
}

function frozen(p) {
  const out = sh('cat /proc/' + p + '/cgroup 2>/dev/null');
  const m = /freezer:(\S*)/.exec(out);
  return m ? m[1] : '?';
}

/**
 * Seconds since the last heartbeat line.
 *
 * Asked for in epoch form and compared against the phone's own clock, so
 * there is no date parsing and no time zone to get wrong - which the first
 * version of this did, and reported a comfortable 0s throughout.
 */
function silentFor() {
  const r = spawnSync(ADB, ['logcat', '-d', '-v', 'epoch', '-s', 'PrintGateway:I'],
    { encoding: 'utf8', maxBuffer: 64 << 20 });
  const lines = (r.stdout || '').split(/\r?\n/).filter((l) => /loginStatus/.test(l));
  if (lines.length === 0) return null;
  const m = /^\s*(\d+)\./.exec(lines[lines.length - 1]);
  if (!m) return null;
  const now = Number(sh('date +%s'));
  if (!now) return null;
  return Math.max(0, now - Number(m[1]));
}

/** Connections the kernel has taken that the app has not read. */
function queued() {
  const out = sh('cat /proc/net/tcp /proc/net/tcp6 2>/dev/null');
  let waiting = 0;
  let bytes = 0;
  for (const line of out.split(/\r?\n/)) {
    const f = line.trim().split(/\s+/);
    if (f.length < 5 || !f[1] || !f[1].endsWith(':' + PORT_HEX)) continue;
    const rx = parseInt((f[4] || '0:0').split(':')[1], 16) || 0;
    if (f[3] === '0A') waiting += rx;       // listening: rx is the accept queue
    else if (f[3] === '01' || f[3] === '08') bytes += rx;  // established / close_wait
  }
  return { waiting, bytes };
}

console.log('watching ' + PKG + ' every ' + (EVERY_MS / 1000) + 's. Ctrl+C to stop.');
console.log('nothing here touches the app - looking at it is what wakes it up.\n');
console.log('time      pid     frozen    silent  queued');
console.log('------------------------------------------------------------');

let lastState = null;
function sample() {
  const p = pid();
  if (!p) {
    console.log(new Date().toTimeString().slice(0, 8) + '  -       GONE      -       -');
    return;
  }
  const f = frozen(p);
  const s = silentFor();
  const q = queued();
  const state = f + '|' + (q.waiting > 0 || q.bytes > 0);
  const flag = f.includes('frozen') ? '  <-- FROZEN'
    : (q.waiting > 0 || q.bytes > 0) ? '  <-- a print is waiting' : '';
  console.log(
    new Date().toTimeString().slice(0, 8) + '  ' +
    p.padEnd(8) + (f || '/').padEnd(10) +
    (s === null ? '-' : s + 's').padEnd(8) +
    (q.waiting + ' conn, ' + q.bytes + ' bytes') + flag,
  );
  if (lastState !== null && state !== lastState) {
    console.log('   ^ state changed');
  }
  lastState = state;
}

sample();
setInterval(sample, EVERY_MS);
