/**
 * Which Omada account and site the live tools act on.
 *
 * Read from the environment and never written into the repo, which is public.
 * Put them in `.env` (gitignored; see `.env.example`). Both appear in every
 * call the portal makes, so the Network tab of a signed-in portal shows them:
 * `.../<account>/api/v2/hotspot/sites/<site>/...`.
 *
 * The Android gateway does not need these - it learns both from whoever signs
 * in on it.
 */

function required(name: string): string {
  const value = process.env[name];
  if (!value) {
    console.error(
      `${name} is not set.\n\n` +
        `  Add it to .env - see .env.example for where to find it.\n`,
    );
    process.exit(1);
  }
  return value;
}

export const accountId = (): string => required('OMADA_ACCOUNT_ID');
export const siteId = (): string => required('OMADA_SITE_ID');
