import { createServer } from 'node:http';
import type { Server } from 'node:http';
import { Router, HttpError, requireString, optionalString, optionalNumber } from './http.ts';
import { config, sites, getSite } from './config.ts';
import { adapter, mock } from './omada/index.ts';
import { issue, reprint, IssueFailedError } from './domain/issuer.ts';
import * as pool from './domain/pool.ts';
import * as janitor from './domain/janitor.ts';
import * as ledger from './store/ledger.ts';
import { printSlip } from './print/printer.ts';
import { handleReceipts } from './loyverse/webhook.ts';
import type { WebhookPayload } from './loyverse/webhook.ts';
import * as clock from './clock.ts';

function requireApiKey(req: { headers: Record<string, unknown> }): void {
  const header = String(req.headers['authorization'] ?? '');
  const token = header.startsWith('Bearer ') ? header.slice(7) : '';
  if (token !== config.apiKey) {
    throw new HttpError(401, 'unauthorized', 'Missing or invalid API key');
  }
}

function requireSimMode(): void {
  if (!mock) {
    throw new HttpError(409, 'not_simulation', 'Simulation routes need OMADA_MODE=mock');
  }
}

export function buildRouter(): Router {
  const r = new Router();

  r.get('/healthz', () => ({
    ok: true,
    mode: adapter.name,
    sites: sites.length,
    simulatedClockOffsetMinutes: clock.offset() / 60_000,
  }));

  // --- catalogue -----------------------------------------------------------

  r.get('/api/v1/sites', ({ req }) => {
    requireApiKey(req);
    return {
      sites: sites.map((s) => ({
        id: s.id,
        name: s.name,
        ssid: s.ssid,
        voucherCap: s.voucherCap,
        plans: s.plans.length,
      })),
    };
  });

  r.get('/api/v1/sites/:siteId/plans', ({ req, params }) => {
    requireApiKey(req);
    const site = getSite(params.siteId!);
    if (!site) throw new HttpError(404, 'unknown_site', `No site ${params.siteId}`);
    return {
      siteId: site.id,
      plans: site.plans.map((p) => ({
        id: p.id,
        name: p.name,
        durationMinutes: p.durationMinutes,
        deviceLimit: p.deviceLimit,
        price: `${p.currency} ${(p.priceMinor / 100).toFixed(2)}`,
        downKbps: p.downKbps,
        upKbps: p.upKbps,
        loyverseSkus: p.loyverseSkus,
      })),
    };
  });

  /** The number that used to force manual cleanup. */
  r.get('/api/v1/sites/:siteId/status', async ({ req, params }) => {
    requireApiKey(req);
    const site = getSite(params.siteId!);
    if (!site) throw new HttpError(404, 'unknown_site', `No site ${params.siteId}`);
    let stored: number | null = null;
    let error: string | undefined;
    try {
      stored = await adapter.countVouchers(site.id);
    } catch (err) {
      error = (err as Error).message;
    }
    return {
      siteId: site.id,
      controller: adapter.name,
      storedVouchers: stored,
      cap: site.voucherCap,
      utilisation: stored === null ? null : Number((stored / site.voucherCap).toFixed(3)),
      highWater: Math.floor(site.voucherCap * config.janitorHighWater),
      pool: pool.status(site.id),
      issuedAllTime: ledger.issuances(site.id).length,
      error,
    };
  });

  // --- the endpoint the POS calls -----------------------------------------

  r.post('/api/v1/vouchers', async ({ req, body }) => {
    requireApiKey(req);
    const siteId = requireString(body, 'siteId');
    const planId = requireString(body, 'planId');
    const reference = optionalString(body, 'reference');
    const idempotencyKey =
      optionalString(body, 'idempotencyKey') ??
      (typeof req.headers['idempotency-key'] === 'string'
        ? (req.headers['idempotency-key'] as string)
        : null);

    try {
      const result = await issue({ siteId, planId, reference, idempotencyKey });
      const print = await printSlip(result.slip);
      return {
        issuanceId: result.issuance.issuanceId,
        code: result.voucher.code,
        siteId,
        planId,
        source: result.source,
        replayed: result.replayed,
        issuedAt: new Date(result.issuance.issuedAt).toISOString(),
        reference: result.issuance.reference,
        slip: result.slip,
        print,
        notes: result.notes,
      };
    } catch (err) {
      if (err instanceof IssueFailedError) {
        throw new HttpError(503, err.cause, err.message);
      }
      throw err;
    }
  });

  r.get('/api/v1/issuances/:issuanceId', ({ req, params }) => {
    requireApiKey(req);
    const found = ledger.findIssuance(params.issuanceId!);
    if (!found) throw new HttpError(404, 'not_found', 'No such issuance');
    return found;
  });

  /** Customer lost the slip: same code, no new voucher. */
  r.post('/api/v1/issuances/:issuanceId/reprint', async ({ req, params }) => {
    requireApiKey(req);
    const result = reprint(params.issuanceId!);
    if (!result) throw new HttpError(404, 'not_found', 'No such issuance');
    const print = await printSlip(result.slip);
    return {
      issuanceId: result.issuance.issuanceId,
      code: result.voucher.code,
      reprints: result.issuance.reprints,
      slip: result.slip,
      print,
    };
  });

  // --- housekeeping --------------------------------------------------------

  r.post('/api/v1/admin/cleanup', async ({ req, body }) => {
    requireApiKey(req);
    const siteId = optionalString(body, 'siteId');
    const reports = siteId ? [await janitor.sweep(siteId)] : await janitor.sweepAll();
    return { reports };
  });

  r.post('/api/v1/admin/pool/refill', async ({ req, body }) => {
    requireApiKey(req);
    const siteId = requireString(body, 'siteId');
    return { siteId, created: await pool.refillSite(siteId), pool: pool.status(siteId) };
  });

  // --- Loyverse ------------------------------------------------------------

  r.post('/webhooks/loyverse', async ({ req, body, query }) => {
    const secret = String(req.headers['x-webhook-secret'] ?? '');
    if (secret !== config.webhookSecret) {
      throw new HttpError(401, 'unauthorized', 'Bad webhook secret');
    }
    const payload = (body ?? {}) as WebhookPayload;
    if (payload.type && !payload.type.startsWith('receipts')) {
      return { ignored: true, reason: `event ${payload.type} is not a receipt` };
    }
    const outcomes = await handleReceipts(payload, query.get('siteId'));
    return { outcomes };
  });

  // --- simulation only -----------------------------------------------------

  /** Pretend a guest typed the code into the captive portal. */
  r.post('/api/v1/sim/redeem', ({ req, body }) => {
    requireApiKey(req);
    requireSimMode();
    const siteId = requireString(body, 'siteId');
    const code = requireString(body, 'code');
    const voucher = mock!.redeem(siteId, code);
    if (!voucher) throw new HttpError(404, 'not_redeemable', 'Code unknown or already used');
    return {
      code: voucher.code,
      status: voucher.status,
      sessionEndsAt: new Date(voucher.usedUntil ?? 0).toISOString(),
    };
  });

  /** Fast-forward the simulated clock so vouchers age in seconds, not days. */
  r.post('/api/v1/sim/advance', ({ req, body }) => {
    requireApiKey(req);
    requireSimMode();
    const minutes = optionalNumber(body, 'minutes', 60);
    return { now: new Date(clock.advance(minutes)).toISOString(), advancedMinutes: minutes };
  });

  /** Cut the controller off, to prove the counter can still sell. */
  r.post('/api/v1/sim/offline', ({ req, body }) => {
    requireApiKey(req);
    requireSimMode();
    const offline = Boolean((body as Record<string, unknown> | null)?.['offline'] ?? true);
    mock!.offline = offline;
    return { controllerOffline: offline };
  });

  /** Fill a site with junk vouchers to watch the cap behaviour. */
  r.post('/api/v1/sim/fill', async ({ req, body }) => {
    requireApiKey(req);
    requireSimMode();
    const siteId = requireString(body, 'siteId');
    const planId = requireString(body, 'planId');
    const count = optionalNumber(body, 'count', 100);
    const created = await adapter.createVouchers(siteId, { planId, count, note: 'sim:fill' });
    return { siteId, planId, created: created.length };
  });

  return r;
}

export function startServer(): Server {
  const router = buildRouter();
  const server = createServer((req, res) => {
    void router.handle(req, res);
  });
  server.listen(config.port, () => {
    console.log(`voucher service listening on http://localhost:${config.port}`);
    console.log(`  controller backend : ${adapter.name}`);
    console.log(`  sites              : ${sites.map((s) => s.id).join(', ')}`);
    console.log(`  janitor            : every ${config.janitorIntervalMinutes} min`);
  });
  return server;
}
