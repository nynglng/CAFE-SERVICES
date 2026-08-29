import type { OmadaAdapter } from './adapter.ts';
import { BackendUnavailableError, QuotaExceededError } from './adapter.ts';
import type { Voucher, CreateVoucherInput } from '../types.ts';
import { getSite, getPlan, config } from '../config.ts';
import { now } from '../clock.ts';

/**
 * ============================ VERIFY BEFORE USE ============================
 * This is the real Omada Open API client. The request/response SHAPES below
 * are written from the documented v1 Open API, but Omada renames and moves
 * hotspot fields between controller versions, so treat every path and field
 * name here as UNCONFIRMED until you have checked them against your own
 * controller API browser:
 *
 *   Controller  -> Settings -> Platform Integration -> Open API  (create an
 *                  app, note client_id / client_secret / Omadac ID)
 *   API browser -> https://<controller>/doc.html   (self-hosted / OC200)
 *                  or the Open API reference for your cloud region.
 *
 * Confirm these four things, then adjust only this file — nothing else in the
 * service knows what an Omada field is called:
 *   1. token endpoint + how the access token is presented on requests
 *   2. the create-voucher body (duration/rate-limit/expiry field names)
 *   3. how codes are read back after creation (group id -> detail call)
 *   4. the delete call, and whether a bulk "delete expired" call exists
 * ==========================================================================
 */

type TokenState = { accessToken: string; refreshToken: string; expiresAt: number };

type OmadaResponse = { errorCode: number; msg?: string; result?: unknown };

export class LiveOmadaAdapter implements OmadaAdapter {
  readonly name = 'live';
  private token: TokenState | null = null;

  private base(): string {
    if (!config.omada.baseUrl || !config.omada.cid) {
      throw new BackendUnavailableError('Omada live mode is not configured');
    }
    return `${config.omada.baseUrl.replace(/\/$/, '')}/openapi/v1/${config.omada.cid}`;
  }

  private async authorize(): Promise<string> {
    if (this.token && this.token.expiresAt > Date.now() + 60_000) {
      return this.token.accessToken;
    }
    const url =
      `${config.omada.baseUrl.replace(/\/$/, '')}/openapi/authorize/token` +
      `?grant_type=client_credentials`;
    const res = await this.fetchJson(url, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        omadacId: config.omada.cid,
        client_id: config.omada.clientId,
        client_secret: config.omada.clientSecret,
      }),
    });
    const result = res.result as { accessToken: string; refreshToken: string; expiresIn: number };
    this.token = {
      accessToken: result.accessToken,
      refreshToken: result.refreshToken,
      expiresAt: Date.now() + result.expiresIn * 1000,
    };
    return this.token.accessToken;
  }

  private async fetchJson(url: string, init: RequestInit): Promise<OmadaResponse> {
    let res: Response;
    try {
      res = await fetch(url, { ...init, signal: AbortSignal.timeout(10_000) });
    } catch (err) {
      // Network-level failure is exactly what the pool fallback exists for.
      throw new BackendUnavailableError(`Omada request failed: ${(err as Error).message}`);
    }
    if (!res.ok) {
      throw new BackendUnavailableError(`Omada returned HTTP ${res.status}`);
    }
    const body = (await res.json()) as OmadaResponse;
    if (body.errorCode !== 0) {
      // Omada signals "too many vouchers" as a business error, not an HTTP one.
      if (/limit|maximum|exceed/i.test(body.msg ?? '')) {
        throw new QuotaExceededError('unknown', 5000);
      }
      throw new Error(`Omada error ${body.errorCode}: ${body.msg ?? 'unknown'}`);
    }
    return body;
  }

  private async call(path: string, init: RequestInit = {}): Promise<OmadaResponse> {
    const accessToken = await this.authorize();
    return this.fetchJson(`${this.base()}${path}`, {
      ...init,
      headers: {
        'content-type': 'application/json',
        Authorization: `AccessToken=${accessToken}`,
        ...(init.headers ?? {}),
      },
    });
  }

  async createVouchers(siteId: string, input: CreateVoucherInput): Promise<Voucher[]> {
    const site = getSite(siteId);
    const plan = getPlan(siteId, input.planId);
    if (!site || !plan) throw new Error(`Unknown site/plan ${siteId}/${input.planId}`);

    // VERIFY: field names for duration, rate limit and expiry vary by version.
    const body = {
      name: `${plan.name} ${new Date(now()).toISOString().slice(0, 16)}`,
      amount: input.count,
      codeLength: plan.codeLength,
      limitType: 1, // limited number of devices
      limitNum: plan.deviceLimit,
      durationType: 0, // countdown starts at first login
      duration: plan.durationMinutes * 60_000,
      timingType: 0, // by time, not by usage
      rateLimit: {
        mode: 0,
        customRateLimit: {
          downLimitEnable: true,
          downLimit: plan.downKbps,
          upLimitEnable: true,
          upLimit: plan.upKbps,
        },
      },
      trafficLimitEnable: false,
      unitPrice: plan.priceMinor,
      currency: plan.currency,
      applyToAllPortals: true,
      expirationTime: now() + plan.unusedValidDays * 86_400_000,
      description: input.note,
    };

    const created = await this.call(`/sites/${site.omadaSiteId}/hotspot/vouchers`, {
      method: 'POST',
      body: JSON.stringify(body),
    });

    // VERIFY: creation returns a voucher GROUP; codes come from its detail call.
    const groupId = (created.result as { id?: string } | undefined)?.id;
    if (!groupId) throw new Error('Omada did not return a voucher group id');
    const detail = await this.call(`/sites/${site.omadaSiteId}/hotspot/vouchers/${groupId}`);
    const rows = ((detail.result as { data?: unknown[] } | undefined)?.data ?? []) as Array<{
      id: string;
      code: string;
    }>;

    const t = now();
    return rows.map((row) => ({
      id: row.id,
      code: row.code,
      siteId,
      planId: plan.id,
      status: 'unused' as const,
      createdAt: t,
      validUntil: t + plan.unusedValidDays * 86_400_000,
      durationMinutes: plan.durationMinutes,
      firstUsedAt: null,
      usedUntil: null,
      note: input.note,
    }));
  }

  async listVouchers(siteId: string): Promise<Voucher[]> {
    const site = getSite(siteId);
    if (!site) throw new Error(`Unknown site ${siteId}`);

    const out: Voucher[] = [];
    const pageSize = 100;
    for (let page = 1; ; page++) {
      const res = await this.call(
        `/sites/${site.omadaSiteId}/hotspot/vouchers?page=${page}&pageSize=${pageSize}`,
      );
      const result = res.result as { data?: unknown[]; totalRows?: number } | undefined;
      const rows = (result?.data ?? []) as Array<{
        id: string;
        code: string;
        status?: number;
        createTime?: number;
        expirationTime?: number;
        duration?: number;
      }>;
      for (const row of rows) {
        out.push({
          id: row.id,
          code: row.code,
          siteId,
          // The controller does not know which of OUR plans a code came from;
          // the local ledger is the source of truth for that.
          planId: 'unknown',
          // VERIFY: 0 unused / 1 in use / 2 expired is the documented mapping.
          status: row.status === 1 ? 'in_use' : row.status === 2 ? 'expired' : 'unused',
          createdAt: row.createTime ?? 0,
          validUntil: row.expirationTime ?? 0,
          durationMinutes: Math.round((row.duration ?? 0) / 60_000),
          firstUsedAt: null,
          usedUntil: null,
          note: '',
        });
      }
      if (rows.length < pageSize) break;
      if (result?.totalRows !== undefined && out.length >= result.totalRows) break;
    }
    return out;
  }

  async deleteVouchers(siteId: string, voucherIds: string[]): Promise<number> {
    const site = getSite(siteId);
    if (!site) throw new Error(`Unknown site ${siteId}`);
    let deleted = 0;
    for (const id of voucherIds) {
      await this.call(`/sites/${site.omadaSiteId}/hotspot/vouchers/${id}`, { method: 'DELETE' });
      deleted++;
    }
    return deleted;
  }

  async countVouchers(siteId: string): Promise<number> {
    return (await this.listVouchers(siteId)).length;
  }
}
