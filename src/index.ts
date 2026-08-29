import { startServer } from './server.ts';
import * as ledger from './store/ledger.ts';
import * as janitor from './domain/janitor.ts';
import * as pool from './domain/pool.ts';
import { sites } from './config.ts';

ledger.load();

const server = startServer();

// Stock the offline reserve at boot, then keep the controller tidy on a timer.
for (const site of sites) {
  void pool.refillSite(site.id).then((created) => {
    const total = Object.values(created).reduce((a, b) => a + b, 0);
    if (total > 0) console.log(`  reserve pool       : +${total} codes for ${site.id}`);
  });
}

janitor.start((reports) => {
  for (const r of reports) {
    if (r.deletedExpired + r.deletedOverflow > 0) {
      console.log(
        `[janitor] ${r.siteId}: removed ${r.deletedExpired + r.deletedOverflow}, ` +
          `now ${r.after}/${r.cap}`,
      );
    }
  }
});

function shutdown(): void {
  janitor.stop();
  ledger.save();
  server.close(() => process.exit(0));
}

process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
