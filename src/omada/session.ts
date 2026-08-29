import { chromium } from 'playwright-core';
import type { BrowserContext, Page } from 'playwright-core';

/**
 * An authenticated session against the Omada Cloud portal's own JSON API.
 *
 * Why this exists: Omada Cloud Essentials does not expose the Open API (that is
 * gated behind the paid Cloud Standard tier), but the portal we log into is a
 * single-page app driven by REST endpoints, and we are a legitimate operator of
 * that portal. This drives a real browser profile to hold the session, then
 * issues the same calls the portal makes.
 *
 * Verified behaviour (see tools/probe-omada-auth.ts):
 *   - `x-requested-with: XMLHttpRequest` is REQUIRED; without it the server 404s
 *   - `csrf-token` must carry the value of the `csrfToken` cookie
 *   - `request-hash` is NOT verified — a bogus value behaves like none, so we
 *     do not reproduce TP-Link's signing scheme
 *   - the portal page must be loaded first, otherwise every call returns
 *     errorCode -1200 ("you have been logged out of the controller")
 */

export type OmadaSessionOptions = {
  profileDir: string;
  chromeExe: string;
  portalUrl: string;
  apiBase: string;
  omadacId: string;
  /** Show the browser window. Useful when the session needs a manual login. */
  headed?: boolean;
  /** How long to let the SPA bootstrap its controller session, in ms. */
  bootstrapMs?: number;
};

export class OmadaSessionError extends Error {
  errorCode: number | undefined;
  constructor(message: string, errorCode?: number) {
    super(message);
    this.name = 'OmadaSessionError';
    this.errorCode = errorCode;
  }
}

/** The controller says our session is gone; the caller should re-open. */
export class OmadaLoggedOutError extends OmadaSessionError {
  constructor(message: string) {
    super(message, -1200);
    this.name = 'OmadaLoggedOutError';
  }
}

type OmadaEnvelope = { errorCode?: number; msg?: string; result?: unknown };

export class OmadaSession {
  private context: BrowserContext | null = null;
  private page: Page | null = null;
  private opts: OmadaSessionOptions;

  constructor(opts: OmadaSessionOptions) {
    this.opts = opts;
  }

  get isOpen(): boolean {
    return this.context !== null;
  }

  /** Launch the browser profile and let the portal establish its session. */
  async open(): Promise<void> {
    if (this.context) return;

    this.context = await chromium.launchPersistentContext(this.opts.profileDir, {
      executablePath: this.opts.chromeExe,
      headless: !this.opts.headed,
      viewport: null,
    });

    this.page = this.context.pages()[0] ?? (await this.context.newPage());
    await this.page
      .goto(this.opts.portalUrl, { waitUntil: 'domcontentloaded' })
      .catch(() => {
        /* the SPA often keeps connections open; a timeout here is not fatal */
      });

    // The controller session is set up by the app after load, not by the
    // navigation itself, so calls made too early get -1200.
    await this.page.waitForTimeout(this.opts.bootstrapMs ?? 12_000);
  }

  async close(): Promise<void> {
    await this.context?.close().catch(() => {});
    this.context = null;
    this.page = null;
  }

  private async csrfToken(): Promise<string> {
    const cookies = (await this.context?.cookies()) ?? [];
    return cookies.find((c) => c.name === 'csrfToken')?.value ?? '';
  }

  private async headers(): Promise<Record<string, string>> {
    return {
      'x-requested-with': 'XMLHttpRequest',
      'csrf-token': await this.csrfToken(),
      accept: 'application/json, text/plain, */*',
      'content-type': 'application/json',
    };
  }

  /** Absolute URL for a controller path like `/api/v2/hotspot/...`. */
  url(path: string): string {
    const base = this.opts.apiBase.replace(/\/$/, '');
    return path.startsWith('/openapi/')
      ? `${base}${path}`
      : `${base}/${this.opts.omadacId}${path}`;
  }

  private unwrap(body: OmadaEnvelope | null, label: string): unknown {
    if (!body) throw new OmadaSessionError(`${label}: no JSON in response`);
    if (body.errorCode === -1200) {
      throw new OmadaLoggedOutError(body.msg ?? 'Logged out of the controller');
    }
    if (body.errorCode !== 0) {
      throw new OmadaSessionError(
        `${label}: ${body.msg ?? 'unknown error'} (errorCode ${body.errorCode})`,
        body.errorCode,
      );
    }
    return body.result ?? null;
  }

  async request(
    method: 'GET' | 'POST' | 'DELETE' | 'PUT' | 'PATCH',
    path: string,
    body?: unknown,
  ): Promise<unknown> {
    if (!this.context) throw new OmadaSessionError('Session is not open');
    const url = this.url(path);
    const res = await this.context.request.fetch(url, {
      method,
      headers: await this.headers(),
      ...(body === undefined ? {} : { data: body }),
    });
    const json = (await res.json().catch(() => null)) as OmadaEnvelope | null;
    return this.unwrap(json, `${method} ${path.split('?')[0]}`);
  }

  get(path: string): Promise<unknown> {
    return this.request('GET', path);
  }

  post(path: string, body: unknown): Promise<unknown> {
    return this.request('POST', path, body);
  }

  del(path: string): Promise<unknown> {
    return this.request('DELETE', path);
  }
}
