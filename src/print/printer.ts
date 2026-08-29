import { appendFileSync, mkdirSync } from 'node:fs';
import { createConnection } from 'node:net';
import { fileURLToPath } from 'node:url';
import { dirname } from 'node:path';
import type { Slip } from './slip.ts';
import { toEscPos } from './slip.ts';

/**
 * Where a finished slip goes.
 *
 *   PRINTER_HOST unset -> simulation: the slip is logged and appended to
 *                         data/print-log.txt so you can see what would print.
 *   PRINTER_HOST set   -> a raw ESC/POS write to the printer on port 9100.
 *
 * Printing must never fail a sale: the code is already minted and the POS has
 * it in the response, so a printer problem is logged, not thrown.
 */

// fileURLToPath, not .pathname — see the note in store/ledger.ts.
const logPath = fileURLToPath(new URL('../../data/print-log.txt', import.meta.url));

export type PrintResult = { printed: boolean; target: string; error?: string };

export async function printSlip(slip: Slip): Promise<PrintResult> {
  const host = process.env.PRINTER_HOST;
  const port = Number(process.env.PRINTER_PORT ?? '9100');

  mkdirSync(dirname(logPath), { recursive: true });
  appendFileSync(logPath, `${slip.text}\n${'='.repeat(32)}\n`, 'utf8');

  if (!host) {
    return { printed: false, target: 'log' };
  }

  return new Promise<PrintResult>((resolve) => {
    const socket = createConnection({ host, port }, () => {
      socket.write(toEscPos(slip), () => socket.end());
    });
    socket.setTimeout(5000);
    socket.on('error', (err) =>
      resolve({ printed: false, target: `${host}:${port}`, error: err.message }),
    );
    socket.on('timeout', () => {
      socket.destroy();
      resolve({ printed: false, target: `${host}:${port}`, error: 'printer timeout' });
    });
    socket.on('close', () => resolve({ printed: true, target: `${host}:${port}` }));
  });
}
