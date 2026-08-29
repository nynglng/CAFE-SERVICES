/**
 * Capture the JSON API that the Omada Cloud web portal calls under the hood.
 *
 * Why this exists: Omada Cloud Essentials has no Open API (that is a paid
 * Cloud Standard feature). But the portal itself is a single-page app driven by
 * its own REST endpoints, and we are an authenticated user of that portal. This
 * records those calls so the voucher adapter can speak to them directly instead
 * of clicking buttons.
 *
 * Re-run this whenever TP-Link changes their site and something breaks — it
 * re-derives the current endpoints and writes them to captured-api.json.
 *
 * Usage:  npm run capture
 *
 * Chrome must be CLOSED first — Chrome locks a profile directory while running.
 *
 * Header VALUES are redacted; only names are recorded, so no cookies, tokens,
 * or passwords are written to disk.
 */

import { chromium } from 'playwright-core';
import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const PROFILE_DIR =
  process.env.CHROME_PROFILE ??
  'C:/Users/USER/Desktop/MY-FOLDER-GITHUB/IXM/Chrome Setup/chrome-profile';
const CHROME_EXE =
  process.env.CHROME_EXE ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const PORTAL = process.env.OMADA_PORTAL ?? 'https://aps1-omada-cloud.tplinkcloud.com/';
// fileURLToPath, not .pathname — .pathname percent-encodes spaces in the
// folder name and silently writes to a "CAFE%20SERVICES" directory.
const OUT = fileURLToPath(new URL('../data/captured-api.json', import.meta.url));

/** Things that must never reach disk. */
const SENSITIVE_HEADERS = new Set([
  'cookie',
  'set-cookie',
  'authorization',
  'proxy-authorization',
]);

type Captured = {
  method: string;
  url: string;
  /** Header names only — values are deliberately dropped. */
  requestHeaderNames: string[];
  requestBody: unknown;
  status: number | null;
  responseBody: unknown;
  at: string;
};

const captured: Captured[] = [];

function redactUrl(url: string): string {
  const u = new URL(url);
  for (const key of [...u.searchParams.keys()]) {
    if (/token|password|secret|key/i.test(key)) u.searchParams.set(key, '<redacted>');
  }
  return u.toString();
}

/** Trim giant payloads so the capture file stays readable. */
function clamp(value: unknown): unknown {
  const text = typeof value === 'string' ? value : JSON.stringify(value);
  if (text === undefined) return value;
  if (text.length > 20_000) return `<truncated ${text.length} chars>`;
  try {
    return typeof value === 'string' ? JSON.parse(value) : value;
  } catch {
    return value;
  }
}

const context = await chromium.launchPersistentContext(PROFILE_DIR, {
  executablePath: CHROME_EXE,
  headless: false,
  viewport: null,
  args: ['--start-maximized'],
});

const page = context.pages()[0] ?? (await context.newPage());

// Listen at the CONTEXT level, not the page level: the Hotspot Manager opens
// in its own tab, and a page-level listener misses every call it makes.
context.on('response', async (response) => {
  const request = response.request();
  const url = request.url();

  // Only the portal's own data calls are interesting.
  if (!/tplinkcloud\.com|tp-link\.com/.test(url)) return;
  if (!/\/api\/|\/openapi\/|\/web\//.test(url)) return;
  if (/\.(js|css|png|jpg|svg|woff2?|ico|map)(\?|$)/.test(url)) return;

  let responseBody: unknown = null;
  try {
    const text = await response.text();
    responseBody = clamp(text);
  } catch {
    responseBody = '<unreadable>';
  }

  let requestBody: unknown = null;
  try {
    requestBody = clamp(request.postData() ?? null);
  } catch {
    requestBody = '<unreadable>';
  }

  captured.push({
    method: request.method(),
    url: redactUrl(url),
    requestHeaderNames: Object.keys(request.headers()).filter(
      (h) => !SENSITIVE_HEADERS.has(h.toLowerCase()),
    ),
    requestBody,
    status: response.status(),
    responseBody,
    at: new Date().toISOString(),
  });

  console.log(`[${response.status()}] ${request.method()} ${url.split('?')[0]}`);
});

/** Results are flushed after every call so they can be read while you work. */
function flush(): void {
  mkdirSync(dirname(OUT), { recursive: true });
  writeFileSync(OUT, JSON.stringify(captured, null, 2), 'utf8');
}

context.on('response', () => flush());

await page.goto(PORTAL, { waitUntil: 'domcontentloaded' }).catch((err) => {
  console.log(`navigation issue: ${(err as Error).message}`);
});

console.log(`
────────────────────────────────────────────────────────────
Portal opened in the automated window.

  1. Log in (if not already)
  2. Go into one of the cafe sites
  3. Open  Hotspot Manager -> Vouchers
  4. Create a voucher (any plan)
  5. Delete a voucher

Every API call is recorded to data/captured-api.json as you click.
Leave this running; it stops on its own after 30 minutes, or when
data/STOP-CAPTURE appears.
────────────────────────────────────────────────────────────
`);

const STOP = fileURLToPath(new URL('../data/STOP-CAPTURE', import.meta.url));
const deadline = Date.now() + 30 * 60_000;
const { existsSync, rmSync } = await import('node:fs');

while (Date.now() < deadline) {
  if (existsSync(STOP)) {
    rmSync(STOP, { force: true });
    break;
  }
  if (context.pages().length === 0) break; // window closed
  await new Promise((r) => setTimeout(r, 2000));
}

flush();
console.log(`\nWrote ${captured.length} calls to ${OUT}`);

await context.close().catch(() => {});
process.exit(0);
