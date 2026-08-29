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

import { createInterface } from 'node:readline/promises';
import { stdin, stdout } from 'node:process';
import { OmadaSession } from '../src/omada/session.ts';
import { OmadaCloudClient, ValidityType } from '../src/omada/cloud.ts';
import type { CloudGroup } from '../src/omada/cloud.ts';

/** Marks a group as created by this tool. Cleanup is limited to these. */
const POS_PREFIX = 'POS-';

/** Defaults matching how the cafe sells WiFi today. */
const SESSION_MINUTES = 120; // access time, counted from first login
const VALID_HOURS = 24; // window in which the code must be redeemed
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
  omadacId: '<omada-account-id>',
  headed: process.env.HEADED === '1',
});

const SITE = process.env.OMADA_SITE_ID ?? '<omada-site-id>';

console.log('Connecting to Omada Cloud (this takes ~15s)...');
await session.open();
const cloud = new OmadaCloudClient(session, SITE);
await cloud.loginStatus();
console.log('Connected.\n');

/** Groups this tool owns — the only ones cleanup may ever delete. */
function ownedByUs(g: CloudGroup): boolean {
  return g.name.startsWith(POS_PREFIX) && g.validityType === ValidityType.FIXED_DATES;
}

function fmt(ms: number | null): string {
  return ms === null ? 'never' : new Date(ms).toLocaleString();
}

async function showStatus(): Promise<void> {
  const groups = await cloud.listGroups();
  const total = groups.reduce((s, g) => s + g.totalCount, 0);
  const ours = groups.filter(ownedByUs);
  const legacy = groups.filter((g) => !ownedByUs(g));
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

async function newSale(quantity: number): Promise<void> {
  const name = `${POS_PREFIX}${new Date().toISOString().slice(0, 19).replace(/[:T]/g, '')}`;
  console.log(`\nCreating ${quantity} voucher${quantity === 1 ? '' : 's'}...`);

  const groupId = await cloud.createGroup({
    name,
    amount: quantity,
    durationMinutes: SESSION_MINUTES,
    codeLength: CODE_LENGTH,
    maxUsers: DEVICES_PER_CODE,
    validityType: ValidityType.FIXED_DATES,
    expireAfterHours: VALID_HOURS,
    description: 'Issued by cafe-voucher-service',
  });

  const codes = await cloud.listCodes(groupId);
  console.log(`\n${'═'.repeat(46)}`);
  for (const v of codes) {
    console.log(`   CODE: ${v.code}`);
  }
  console.log(`${'═'.repeat(46)}`);
  console.log(`   ${SESSION_MINUTES / 60} hours of access, from first login`);
  console.log(`   Must be used before: ${fmt(codes[0]?.endTime ?? null)}`);
  console.log(`   group: ${name}\n`);
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
        (ownedByUs(g) ? '  this tool' : '  legacy (leave alone)'),
    );
  }
}

async function cleanup(dryRun: boolean): Promise<void> {
  const groups = await cloud.listGroups();
  const candidates = groups.filter(ownedByUs);

  if (candidates.length === 0) {
    console.log('\nNothing to clean: this tool has not created any groups yet.');
    return;
  }

  // A group is spent when none of its codes are still waiting to be redeemed.
  const spent: CloudGroup[] = [];
  for (const g of candidates) {
    const codes = await cloud.listCodes(g.id);
    const stillUsable = codes.some((c) => c.status === 0);
    if (!stillUsable) spent.push(g);
  }

  if (spent.length === 0) {
    console.log(`\nAll ${candidates.length} of this tool's groups still have unused codes.`);
    return;
  }

  console.log(`\n${dryRun ? 'Would delete' : 'Deleting'} ${spent.length} spent group(s):`);
  for (const g of spent) {
    console.log(`   ${g.name}  (${g.totalCount} codes, ${g.usedCount} used)`);
    if (!dryRun) await cloud.deleteGroup(g.id);
  }
  if (dryRun) console.log('\n   (dry run — nothing deleted. Run "cleanup!" to confirm.)');
}

function help(): void {
  console.log(`
Commands:
  new sale [qty]   create qty vouchers (default 1) on the live site, 24h validity
  list             every voucher group on the site, marked by owner
  status           stored vouchers vs the 5000 cap
  cleanup          show which of THIS TOOL's groups are spent (dry run)
  cleanup!         actually delete those spent groups
  help             show this
  exit             quit

Your existing printed batches are legacy/permanent and are never touched.
`);
}

async function run(line: string): Promise<void> {
  const parts = line.trim().split(/\s+/);
  const cmd = parts[0]?.toLowerCase();
  if (!cmd) return;

  try {
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
