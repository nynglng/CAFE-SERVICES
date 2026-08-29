import { readFileSync, existsSync } from 'node:fs';
import type { Site, Plan } from './types.ts';

/** Minimal .env reader — avoids a dependency for six settings. */
function loadDotenv(path: string): void {
  if (!existsSync(path)) return;
  for (const raw of readFileSync(path, 'utf8').split('\n')) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    const eq = line.indexOf('=');
    if (eq === -1) continue;
    const key = line.slice(0, eq).trim();
    const value = line.slice(eq + 1).trim().replace(/^["']|["']$/g, '');
    if (!(key in process.env)) process.env[key] = value;
  }
}

loadDotenv(new URL('../.env', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'));

function env(key: string, fallback: string): string {
  const v = process.env[key];
  return v === undefined || v === '' ? fallback : v;
}

const sitesFile = new URL('../config/sites.json', import.meta.url);
const parsed = JSON.parse(readFileSync(sitesFile, 'utf8')) as { sites: Site[] };

export const sites: Site[] = parsed.sites;

export const config = {
  port: Number(env('PORT', '8787')),
  apiKey: env('API_KEY', 'dev-pos-key-change-me'),
  webhookSecret: env('LOYVERSE_WEBHOOK_SECRET', 'dev-webhook-secret-change-me'),
  omadaMode: env('OMADA_MODE', 'mock'),
  omada: {
    baseUrl: env('OMADA_BASE_URL', ''),
    cid: env('OMADA_CID', ''),
    clientId: env('OMADA_CLIENT_ID', ''),
    clientSecret: env('OMADA_CLIENT_SECRET', ''),
  },
  janitorIntervalMinutes: Number(env('JANITOR_INTERVAL_MINUTES', '15')),
  janitorHighWater: Number(env('JANITOR_HIGH_WATER', '0.75')),
};

export function getSite(siteId: string): Site | undefined {
  return sites.find((s) => s.id === siteId);
}

export function getPlan(siteId: string, planId: string): Plan | undefined {
  return getSite(siteId)?.plans.find((p) => p.id === planId);
}

/** Resolve a Loyverse line item (by SKU, else by name) to a configured plan. */
export function planForSku(siteId: string, sku: string): Plan | undefined {
  const site = getSite(siteId);
  if (!site) return undefined;
  const needle = sku.trim().toUpperCase();
  return site.plans.find(
    (p) =>
      p.loyverseSkus.some((s) => s.toUpperCase() === needle) ||
      p.name.toUpperCase() === needle,
  );
}
