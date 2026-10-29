/**
 * Live voucher terminal — talks to the REAL Omada Cloud site.
 *
 * Simulates what the POS will eventually do: a sale asks for N vouchers, the
 * service creates them on the controller with a 24-hour validity window, and
 * hands back the codes to print.
 *
 * SAFETY: the existing hand-printed voucher batches are the fallback when this
 * automation is down, so they must never be touched. Every group this tool
 * creates is named with the POS_PREFIX below, and `cleanup` will only ever
 * delete groups that BOTH carry that prefix AND use Fixed Dates validity. The
 * legacy Permanent groups therefore cannot be selected, even by accident.
 *
 * Usage:  npm run live
 */

import './_live-errors.ts';
import { createInterface } from 'node:readline/promises';
import { stdin, stdout } from 'node:process';
import { OmadaSession } from '../src/omada/session.ts';
import { OmadaCloudClient, ValidityType } from '../src/omada/cloud.ts';
import {
  SERVICE_TAG,
  POS_PREFIX,
  ownedByService,
  findSpentGroups,
} from '../src/omada/housekeeping.ts';
import type { CloudGroup } from '../src/omada/cloud.ts';
import { accountId, siteId } from './_account.ts';

/** Defaults matching how the cafe sells WiFi today. */
const SESSION_MINUTES = 120; // access time, counted from first login
// Window in which a code must be redeemed. Overridable so the expiry and
// cleanup behaviour can be exercised without waiting a day.
const VALID_HOURS = Number(process.env.VOUCHER_VALID_HOURS) || 24;
const CODE_LENGTH = 6;
const DEVICES_PER_CODE = 1;

const session = new OmadaSession({
  profileDir:
    process.env.CHROME_PROFILE ??
    'C:/Users/USER/Desktop/MY-FOLDER-GITHUB/IXM/Chrome Setup/chrome-profile',
  chromeExe:
    process.env.CHROME_EXE ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe',
  portalUrl: 'https://aps1-omada-cloud.tplinkcloud.com/',
  apiBase: 'https://aps1-api-omada-essential-controller.tplinkcloud.com',
  omadacId: accountId(),
  headed: process.env.HEADED === '1',
});

const SITE = siteId();

console.log('Connecting to Omada Cloud (this takes ~15s)...');
await session.open();
const cloud = new OmadaCloudClient(session, SITE);
await cloud.loginStatus();
console.log('Connected.\n');

function fmt(ms: number | null): string {
  return ms === null ? 'never' : new Date(ms).toLocaleString();
}

async function showStatus(): Promise<void> {
  const groups = await cloud.listGroups();
  const total = groups.reduce((s, g) => s + g.totalCount, 0);
  const ours = groups.filter(ownedByService);
  const legacy = groups.filter((g) => !ownedByService(g));
  console.log(`\nStored vouchers: ${total} / 5000`);
  console.log(
    `  legacy (printed, untouched): ${legacy.length} groups, ` +
      `${legacy.reduce((s, g) => s + g.totalCount, 0)} vouchers`,
  );
  console.log(
    `  created by this tool       : ${ours.length} groups, ` +
      `${ours.reduce((s, g) => s + g.totalCount, 0)} vouchers`,
  );
}

/** Local-time stamp, so group names match what the Omada UI shows. */
function stamp(): string {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}`;
}

async function newSale(quantity: number): Promise<void> {
  const name = `${POS_PREFIX}${stamp()}`;
  console.log(`\nCreating ${quantity} voucher${quantity === 1 ? '' : 's'}...`);

  const groupId = await cloud.createGroup({
    name,
    amount: quantity,
    durationMinutes: SESSION_MINUTES,
    codeLength: CODE_LENGTH,
    maxUsers: DEVICES_PER_CODE,
    validityType: ValidityType.FIXED_DATES,
    expireAfterHours: VALID_HOURS,
    description: SERVICE_TAG,
  });

  const codes = await cloud.listCodes(groupId);
  const bar = '═'.repeat(46);

  console.log('');
  console.log(bar);
  console.log(`  SALE — ${codes.length} voucher${codes.length === 1 ? '' : 's'}`);
  console.log(bar);
  console.log('');
  codes.forEach((v, i) => {
    console.log(`   ${String(i + 1).padStart(2)}.   ${v.code}`);
  });
  console.log('');
  console.log('─'.repeat(46));
  console.log(`   Access  : ${SESSION_MINUTES / 60} hours from first login`);
  console.log(`   Expires : ${fmt(codes[0]?.endTime ?? null)}`);
  console.log(`   Group   : ${name}`);
  console.log(bar);
  console.log('');

  // Housekeeping happens after the codes are shown: the sale is already done,
  // the cashier is not kept waiting, and a failure here cannot undo it.
  try {
    await cleanup(false, true);
  } catch (err) {
    console.log(`   (auto-cleanup skipped: ${(err as Error).message})`);
  }
}

async function listGroups(): Promise<void> {
  const groups = await cloud.listGroups();
  console.log(
    '\n' +
      'NAME'.padEnd(24) +
      'TOTAL'.padStart(6) +
      'UNUSED'.padStart(7) +
      'USED'.padStart(6) +
      '  VALIDITY   OWNER',
  );
  for (const g of groups.sort((a, b) => a.createdTime - b.createdTime)) {
    console.log(
      g.name.slice(0, 23).padEnd(24) +
        String(g.totalCount).padStart(6) +
        String(g.unusedCount).padStart(7) +
        String(g.usedCount).padStart(6) +
        (g.validityType === ValidityType.FIXED_DATES ? '  expiring ' : '  permanent') +
        (ownedByService(g) ? '  this tool' : '  legacy (leave alone)'),
    );
  }
}

async function cleanup(dryRun: boolean, quiet = false): Promise<void> {
  const { candidates, spent } = await findSpentGroups(cloud);

  if (candidates.length === 0) {
    if (!quiet) console.log('Nothing to clean: this tool has not created any groups yet.');
    return;
  }

  if (spent.length === 0) {
    if (!quiet) {
      console.log(`All ${candidates.length} of this tool's groups still have unused codes.`);
    }
    return;
  }

  if (quiet) {
    // After a sale: one line, and only when something actually happened.
    for (const g of spent) await cloud.deleteGroup(g.id);
    const freed = spent.reduce((n, g) => n + g.totalCount, 0);
    console.log(`   housekeeping: removed ${spent.length} spent group(s), ${freed} slot(s) freed`);
    return;
  }

  console.log(`${dryRun ? 'Would delete' : 'Deleting'} ${spent.length} spent group(s):`);
  for (const g of spent) {
    console.log(`   ${g.name}  (${g.totalCount} codes, ${g.usedCount} used)`);
    if (!dryRun) await cloud.deleteGroup(g.id);
  }
  if (dryRun) console.log('   (dry run — nothing deleted. Run "cleanup!" to confirm.)');
}

function help(): void {
  console.log(`
Commands:
  voucher-sale-N   a sale for N vouchers (voucher-sale-1, voucher-sale-3, ...)
                   2 hours of access from first login; group expires in 24h
  new sale [qty]   same thing, older spelling
  list             every voucher group on the site, marked by owner
  status           stored vouchers vs the 5000 cap
  cleanup          show which of THIS TOOL's groups are spent (dry run)
  cleanup!         actually delete those spent groups
  help             show this
  exit             quit

Expiry marks vouchers EXPIRED, it does NOT delete them -- they keep counting
toward the 5000 cap until a group is removed, here or in the Omada UI.

Your existing printed batches are legacy/permanent and are never touched.
`);
}

async function run(line: string): Promise<void> {
  const parts = line.trim().split(/\s+/);
  const cmd = parts[0]?.toLowerCase();
  if (!cmd) return;

  try {
    // The POS asks for a number of vouchers: voucher-sale-3 means three.
    const sale = cmd.match(/^voucher-sale-(\d+)$/);
    if (sale) {
      const qty = Number(sale[1]);
      if (!Number.isInteger(qty) || qty < 1 || qty > 5000) {
        console.log('Amount must be between 1 and 5000 — the Omada limit.');
        return;
      }
      await newSale(qty);
      return;
    }

    switch (cmd) {
      case 'help':
        help();
        break;
      case 'exit':
      case 'quit':
        await session.close();
        process.exit(0);
        break;
      case 'new': {
        if (parts[1]?.toLowerCase() !== 'sale') {
          console.log('usage: new sale [qty]');
          break;
        }
        const qty = Math.max(1, Math.round(Number(parts[2] ?? '1')));
        if (!Number.isFinite(qty)) {
          console.log('usage: new sale [qty]');
          break;
        }
        await newSale(qty);
        break;
      }
      case 'list':
        await listGroups();
        break;
      case 'status':
        await showStatus();
        break;
      case 'cleanup':
        await cleanup(true);
        break;
      case 'cleanup!':
        await cleanup(false);
        break;
      default:
        console.log(`unknown command "${cmd}" — type "help"`);
    }
  } catch (err) {
    console.log(`!! ${(err as Error).message}`);
  }
}

help();
await showStatus();

const rl = createInterface({ input: stdin, output: stdout, prompt: '\n> ' });

// Commands are async, so chain them onto one queue to keep order. Piped input
// emits 'close' while the queue is still draining, hence the `closed` guard —
// prompting a closed readline throws ERR_USE_AFTER_CLOSE.
let queue: Promise<void> = Promise.resolve();
let closed = false;

rl.prompt();
rl.on('line', (line) => {
  queue = queue.then(() => run(line)).then(() => {
    if (!closed) rl.prompt();
  });
});
rl.on('close', () => {
  closed = true;
  queue.then(() => session.close()).then(() => process.exit(0));
});
