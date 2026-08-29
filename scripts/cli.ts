/**
 * Interactive terminal simulation of the voucher system. No POS, no HTTP, no
 * client access needed — just type a command and see what the "hotspot"
 * would show.
 *
 * Run it with:  npm run cli
 */

import { createInterface } from 'node:readline/promises';
import { stdin, stdout } from 'node:process';

process.env.OMADA_MODE = 'mock';

const ledger = await import('../src/store/ledger.ts');
const { issue, reprint } = await import('../src/domain/issuer.ts');
const { adapter, mock } = await import('../src/omada/index.ts');
const janitor = await import('../src/domain/janitor.ts');
const pool = await import('../src/domain/pool.ts');
const clock = await import('../src/clock.ts');
const { sites, getSite } = await import('../src/config.ts');

ledger.load();

let currentSiteId = sites[0]!.id;

function site() {
  return getSite(currentSiteId)!;
}

function fmt(ms: number): string {
  if (!ms) return '-';
  const d = new Date(ms);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getMonth() + 1}/${d.getDate()} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function printBanner(): void {
  console.log('\nCafe Voucher Simulator — type "help" for commands, "exit" to quit.\n');
  printSiteHeader();
}

function printSiteHeader(): void {
  const s = site();
  console.log(
    `Site: ${s.id} (${s.name})   Plans: ${s.plans.map((p) => p.id).join(', ')}   ` +
      `Clock: ${new Date(clock.now()).toLocaleString()}\n`,
  );
}

async function printHotspotTable(): Promise<void> {
  const vouchers = await adapter.listVouchers(currentSiteId);
  if (vouchers.length === 0) {
    console.log('(hotspot voucher list is empty)');
    return;
  }
  console.log(
    'CODE'.padEnd(10) +
      'PLAN'.padEnd(8) +
      'STATUS'.padEnd(10) +
      'VALID UNTIL'.padEnd(14) +
      'SESSION ENDS'.padEnd(14) +
      'NOTE',
  );
  for (const v of vouchers.sort((a, b) => a.createdAt - b.createdAt)) {
    console.log(
      v.code.padEnd(10) +
        v.planId.padEnd(8) +
        v.status.padEnd(10) +
        fmt(v.validUntil).padEnd(14) +
        (v.usedUntil ? fmt(v.usedUntil) : '-').padEnd(14) +
        v.note,
    );
  }
  console.log(`\n${vouchers.length} stored / ${site().voucherCap} cap`);
}

function help(): void {
  console.log(`
Commands:
  new sale <planId> [ref]     issue a voucher, as if a POS just rang up a plan
  list                        show every voucher currently on the hotspot
  status                      cap usage + reserve pool for the current site
  redeem <code>               simulate a guest logging in with that code
  advance <minutes>           fast-forward the clock (ages vouchers)
  cleanup                     run the janitor sweep now
  refill                      top up the offline reserve pool
  offline                     toggle simulated controller/internet outage
  site <siteId>               switch site (${sites.map((s) => s.id).join(', ')})
  plans                       list plans for the current site
  reprint <issuanceId>        reprint a slip without minting a new voucher
  help                        show this again
  exit                        quit
`);
}

async function run(line: string): Promise<void> {
  const [cmd, ...rest] = line.trim().split(/\s+/);
  if (!cmd) return;

  switch (cmd.toLowerCase()) {
    case 'help':
      help();
      break;

    case 'exit':
    case 'quit':
      ledger.save();
      process.exit(0);
      break;

    case 'new': {
      // "new sale <planId> [ref]"
      if (rest[0]?.toLowerCase() !== 'sale' || !rest[1]) {
        console.log('usage: new sale <planId> [reference]');
        break;
      }
      const planId = rest[1];
      const reference = rest[2] ?? null;
      try {
        const result = await issue({ siteId: currentSiteId, planId, reference });
        console.log(
          `\n>> voucher code: ${result.voucher.code}   ` +
            `(source=${result.source}${result.replayed ? ', replayed' : ''})`,
        );
        console.log(
          `   plan=${planId}  valid to redeem until ${fmt(result.voucher.validUntil)}, ` +
            `then ${site().plans.find((p) => p.id === planId)?.durationMinutes} min once used`,
        );
        console.log(`   issuanceId: ${result.issuance.issuanceId}`);
        for (const n of result.notes) console.log(`   note: ${n}`);
        console.log();
      } catch (err) {
        console.log(`!! ${(err as Error).message}`);
      }
      break;
    }

    case 'list':
      await printHotspotTable();
      break;

    case 'status': {
      const stored = await adapter.countVouchers(currentSiteId);
      const s = site();
      console.log(
        `stored ${stored}/${s.voucherCap}  |  reserve: ` +
          pool
            .status(currentSiteId)
            .map((p) => `${p.planId}:${p.have}/${p.target}`)
            .join(' '),
      );
      break;
    }

    case 'redeem': {
      const code = rest[0];
      if (!code) {
        console.log('usage: redeem <code>');
        break;
      }
      const v = mock?.redeem(currentSiteId, code);
      if (!v) {
        console.log(`!! code ${code} not found or already used`);
        break;
      }
      console.log(`>> redeemed. Session runs until ${fmt(v.usedUntil!)}`);
      break;
    }

    case 'advance': {
      const minutes = Number(rest[0] ?? '60');
      if (!Number.isFinite(minutes)) {
        console.log('usage: advance <minutes>');
        break;
      }
      clock.advance(minutes);
      console.log(`>> clock is now ${new Date(clock.now()).toLocaleString()}`);
      break;
    }

    case 'cleanup': {
      const report = await janitor.sweep(currentSiteId);
      console.log(
        `>> removed ${report.deletedExpired} spent + ${report.deletedOverflow} overflow ` +
          `-> ${report.after}/${report.cap}`,
      );
      break;
    }

    case 'refill': {
      const created = await pool.refillSite(currentSiteId);
      const total = Object.values(created).reduce((a, b) => a + b, 0);
      console.log(`>> reserve topped up by ${total} codes`);
      break;
    }

    case 'offline': {
      if (!mock) {
        console.log('!! only available in mock mode');
        break;
      }
      mock.offline = !mock.offline;
      console.log(`>> controller is now ${mock.offline ? 'OFFLINE' : 'back online'}`);
      break;
    }

    case 'site': {
      const id = rest[0];
      if (!id || !getSite(id)) {
        console.log(`usage: site <${sites.map((s) => s.id).join('|')}>`);
        break;
      }
      currentSiteId = id;
      printSiteHeader();
      break;
    }

    case 'plans': {
      for (const p of site().plans) {
        console.log(
          `  ${p.id.padEnd(6)} ${p.name.padEnd(18)} ${p.durationMinutes}min  ` +
            `pool target ${p.poolTarget}  expires unused after ${p.unusedValidDays}d`,
        );
      }
      break;
    }

    case 'reprint': {
      const id = rest[0];
      if (!id) {
        console.log('usage: reprint <issuanceId>');
        break;
      }
      const result = reprint(id);
      if (!result) {
        console.log('!! no such issuance');
        break;
      }
      console.log(`>> code ${result.voucher.code}  (reprint #${result.issuance.reprints})`);
      break;
    }

    default:
      console.log(`unknown command "${cmd}" — type "help"`);
  }
}

printBanner();
const rl = createInterface({ input: stdin, output: stdout, prompt: '> ' });

// readline fires 'line' events without waiting for our async handler, and a
// piped/EOF input can emit 'close' before a command finishes running. Chain
// every command onto one queue so order and completion are both guaranteed.
let queue: Promise<void> = Promise.resolve();

rl.prompt();
rl.on('line', (line) => {
  queue = queue.then(() => run(line)).then(() => rl.prompt());
});
rl.on('close', () => {
  queue
    .then(() => ledger.save())
    .then(() => process.exit(0));
});
