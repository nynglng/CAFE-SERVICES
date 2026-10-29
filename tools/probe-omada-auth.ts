/**
 * Work out exactly what a caller must do to talk to the Omada hotspot API.
 *
 * Established so far:
 *   - `request-hash` is NOT verified: a deliberately bogus value returns the
 *     same result as none at all, so the adapter does not need to reproduce
 *     TP-Link's signing scheme.
 *   - `x-requested-with: XMLHttpRequest` IS required, otherwise the server 404s.
 *   - A bare cookie jar is not enough: the controller replies -1200
 *     ("you have been logged out") until a controller/hotspot session exists.
 *
 * This probe drives the real portal so the session gets bootstrapped, then
 * retries the API calls to confirm what makes them succeed.
 *
 * Usage:  npm run probe        (Chrome must be closed)
 */

import { chromium } from 'playwright-core';
import { accountId, siteId } from './_account.ts';

const PROFILE_DIR =
  process.env.CHROME_PROFILE ??
  'C:/Users/USER/Desktop/MY-FOLDER-GITHUB/IXM/Chrome Setup/chrome-profile';
const CHROME_EXE =
  process.env.CHROME_EXE ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe';

const PORTAL = 'https://aps1-omada-cloud.tplinkcloud.com/';
const API = 'https://aps1-api-omada-essential-controller.tplinkcloud.com';
const OMADAC = accountId();
const SITE = siteId();

const context = await chromium.launchPersistentContext(PROFILE_DIR, {
  executablePath: CHROME_EXE,
  headless: false,
  viewport: null,
});

const page = context.pages()[0] ?? (await context.newPage());

console.log('Loading the portal so the controller session is established...');
await page.goto(PORTAL, { waitUntil: 'domcontentloaded' }).catch(() => {});
// The SPA bootstraps its controller session after load; give it room.
await page.waitForTimeout(12_000);
console.log(`Portal settled at: ${page.url()}\n`);

async function csrf(): Promise<string> {
  const cookies = await context.cookies();
  return cookies.find((c) => c.name === 'csrfToken')?.value ?? '';
}

const baseHeaders = (token: string): Record<string, string> => ({
  'x-requested-with': 'XMLHttpRequest',
  'csrf-token': token,
  accept: 'application/json, text/plain, */*',
});

async function call(
  label: string,
  url: string,
  headers: Record<string, string>,
): Promise<unknown> {
  try {
    const res = await context.request.get(url, { headers });
    const body = (await res.json().catch(() => null)) as {
      errorCode?: number;
      msg?: string;
      result?: unknown;
    } | null;
    const rows = (body?.result as { data?: unknown[] } | undefined)?.data?.length;
    console.log(
      `${label.padEnd(30)} HTTP ${res.status()}  errorCode=${body?.errorCode}  ` +
        `rows=${rows ?? '-'}  msg=${body?.msg ?? ''}`,
    );
    return body?.result ?? null;
  } catch (err) {
    console.log(`${label.padEnd(30)} threw: ${(err as Error).message}`);
    return null;
  }
}

const token = await csrf();
console.log(`csrfToken cookie present: ${token ? 'yes' : 'NO'}\n${'─'.repeat(78)}`);

// 1. Does the hotspot session report us as logged in?
await call(
  'hotspot/loginStatus',
  `${API}/${OMADAC}/api/v2/hotspot/loginStatus?needToken=true`,
  baseHeaders(token),
);

// 2. The call that actually matters for the adapter.
await call(
  'voucherGroups (list)',
  `${API}/${OMADAC}/api/v2/hotspot/sites/${SITE}/voucherGroups` +
    `?currentPage=1&currentPageSize=5&searchField=name%2Ccode`,
  baseHeaders(token),
);

// 3. Same call issued from inside the page, which carries every ambient header
//    the app itself would send. If this succeeds where the above fails, the
//    adapter should route through the page rather than the request context.
console.log('\nSame list call, issued from inside the page context:');
const inPage = await page.evaluate(
  async ([api, omadac, site, tok]) => {
    try {
      const res = await fetch(
        `${api}/${omadac}/api/v2/hotspot/sites/${site}/voucherGroups` +
          `?currentPage=1&currentPageSize=5&searchField=name%2Ccode`,
        {
          headers: {
            'x-requested-with': 'XMLHttpRequest',
            'csrf-token': tok,
            accept: 'application/json, text/plain, */*',
          },
          credentials: 'include',
        },
      );
      const json = await res.json();
      return {
        status: res.status,
        errorCode: json?.errorCode,
        msg: json?.msg,
        rows: json?.result?.data?.length ?? null,
      };
    } catch (err) {
      return { error: String(err) };
    }
  },
  [API, OMADAC, SITE, token] as const,
);
console.log('  ' + JSON.stringify(inPage));

await context.close();
process.exit(0);
