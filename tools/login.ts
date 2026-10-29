/**
 * Refresh the Omada Cloud session.
 *
 * The live tools rely on a Chrome profile that is logged into Omada. That
 * session expires every so often, and every tool then fails with errorCode
 * -1200. This opens a visible window, waits for you to log in, and confirms the
 * controller accepts us again.
 *
 * Nothing is stored in the repo — the session lives in the Chrome profile.
 *
 * Usage:  npm run login
 */

import './_live-errors.ts';
import { OmadaSession } from '../src/omada/session.ts';
import { accountId } from './_account.ts';

const session = new OmadaSession({
  profileDir:
    process.env.CHROME_PROFILE ??
    'C:/Users/USER/Desktop/MY-FOLDER-GITHUB/IXM/Chrome Setup/chrome-profile',
  chromeExe:
    process.env.CHROME_EXE ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe',
  portalUrl: 'https://aps1-omada-cloud.tplinkcloud.com/',
  apiBase: 'https://aps1-api-omada-essential-controller.tplinkcloud.com',
  omadacId: accountId(),
  headed: true,
  bootstrapMs: 3_000,
  skipReady: true,
});

console.log(`
────────────────────────────────────────────────────────────
A Chrome window is opening on the Omada Cloud portal.

  Log in there if it asks, and open your site's dashboard.

I am checking every 5 seconds and will exit on my own as soon
as the controller accepts the session. Up to 5 minutes.
────────────────────────────────────────────────────────────
`);

await session.open();

const deadline = Date.now() + 5 * 60_000;
let ok = false;

while (Date.now() < deadline) {
  try {
    const status = (await session.get('/api/v2/hotspot/loginStatus?needToken=true')) as {
      login?: boolean;
    } | null;
    if (status?.login === true) {
      ok = true;
      break;
    }
    console.log('  waiting for you to sign in...');
    await new Promise((r) => setTimeout(r, 5_000));
    continue;
  } catch (err) {
    const msg = (err as Error).message;
    process.stdout.write(`  waiting... (${msg.slice(0, 60)})\n`);
    await new Promise((r) => setTimeout(r, 5_000));
  }
}

console.log(
  ok
    ? '\nSession is live. The other live tools will work now.'
    : '\nStill not logged in after 5 minutes — run this again when ready.',
);

await session.close();
process.exit(ok ? 0 : 1);
