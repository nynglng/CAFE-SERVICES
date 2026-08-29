/**
 * Turn the two failures that actually happen when running the live tools into
 * instructions instead of stack traces. Importing this installs the handlers.
 *
 * The tools use top-level await, so a thrown error surfaces as an unhandled
 * rejection rather than something a try/catch in the tool would see.
 */

function explain(err: unknown): string | null {
  const message = err instanceof Error ? err.message : String(err);
  const code = (err as { errorCode?: number } | null)?.errorCode;

  if (code === -1200 || /logged out of the controller/i.test(message)) {
    return (
      'The Omada session has expired.\n\n' +
      '  Run:  npm run login\n\n' +
      'That opens a window, you log in, and it exits by itself.\n' +
      'Then run this command again.'
    );
  }
  if (/ProcessSingleton|profile.*in use|SingletonLock/i.test(message)) {
    return (
      'That Chrome profile is already open, so the tools cannot use it.\n' +
      'Close the automated Chrome window and try again.'
    );
  }
  return null;
}

function report(err: unknown): never {
  const friendly = explain(err);
  if (friendly) {
    console.error(`\n${friendly}`);
  } else {
    console.error(`\n${err instanceof Error ? (err.stack ?? err.message) : String(err)}`);
  }
  process.exit(1);
}

process.on('unhandledRejection', report);
process.on('uncaughtException', report);
