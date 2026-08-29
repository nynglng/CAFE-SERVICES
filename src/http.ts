import type { IncomingMessage, ServerResponse } from 'node:http';

/**
 * A router small enough to read in one sitting. Six endpoints do not justify a
 * framework, and a zero-dependency service is one less thing to patch on a box
 * sitting behind a cafe counter.
 */

export type Ctx = {
  req: IncomingMessage;
  res: ServerResponse;
  params: Record<string, string>;
  query: URLSearchParams;
  body: unknown;
};

export type Handler = (ctx: Ctx) => Promise<unknown> | unknown;

type Route = { method: string; segments: string[]; handler: Handler };

export class HttpError extends Error {
  status: number;
  code: string;
  constructor(status: number, code: string, message: string) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

export class Router {
  private routes: Route[] = [];

  add(method: string, pattern: string, handler: Handler): void {
    this.routes.push({
      method,
      segments: pattern.split('/').filter(Boolean),
      handler,
    });
  }

  get(pattern: string, handler: Handler): void {
    this.add('GET', pattern, handler);
  }

  post(pattern: string, handler: Handler): void {
    this.add('POST', pattern, handler);
  }

  private match(method: string, path: string): { route: Route; params: Record<string, string> } | null {
    const parts = path.split('/').filter(Boolean);
    for (const route of this.routes) {
      if (route.method !== method) continue;
      if (route.segments.length !== parts.length) continue;
      const params: Record<string, string> = {};
      let ok = true;
      for (let i = 0; i < parts.length; i++) {
        const seg = route.segments[i]!;
        if (seg.startsWith(':')) params[seg.slice(1)] = decodeURIComponent(parts[i]!);
        else if (seg !== parts[i]) {
          ok = false;
          break;
        }
      }
      if (ok) return { route, params };
    }
    return null;
  }

  async handle(req: IncomingMessage, res: ServerResponse): Promise<void> {
    const url = new URL(req.url ?? '/', `http://${req.headers.host ?? 'localhost'}`);
    const found = this.match(req.method ?? 'GET', url.pathname);

    if (!found) {
      send(res, 404, { error: { code: 'not_found', message: `No route for ${req.method} ${url.pathname}` } });
      return;
    }

    let body: unknown = null;
    if (req.method === 'POST' || req.method === 'PUT' || req.method === 'PATCH') {
      try {
        body = await readJson(req);
      } catch (err) {
        send(res, 400, { error: { code: 'bad_json', message: (err as Error).message } });
        return;
      }
    }

    try {
      const result = await found.route.handler({
        req,
        res,
        params: found.params,
        query: url.searchParams,
        body,
      });
      if (!res.writableEnded) send(res, 200, result ?? { ok: true });
    } catch (err) {
      if (err instanceof HttpError) {
        send(res, err.status, { error: { code: err.code, message: err.message } });
        return;
      }
      const message = (err as Error).message ?? 'Unexpected error';
      send(res, 500, { error: { code: 'internal_error', message } });
    }
  }
}

export function send(res: ServerResponse, status: number, payload: unknown): void {
  const json = JSON.stringify(payload, null, 2);
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(json),
  });
  res.end(json);
}

async function readJson(req: IncomingMessage): Promise<unknown> {
  const chunks: Buffer[] = [];
  let size = 0;
  for await (const chunk of req) {
    const buf = chunk as Buffer;
    size += buf.length;
    if (size > 1_000_000) throw new Error('Request body too large');
    chunks.push(buf);
  }
  if (chunks.length === 0) return null;
  const raw = Buffer.concat(chunks).toString('utf8').trim();
  if (!raw) return null;
  return JSON.parse(raw);
}

/** Read a required string field off a JSON body, with a clear error if absent. */
export function requireString(body: unknown, field: string): string {
  const value = (body as Record<string, unknown> | null)?.[field];
  if (typeof value !== 'string' || value.trim() === '') {
    throw new HttpError(400, 'invalid_request', `Field "${field}" is required`);
  }
  return value.trim();
}

export function optionalString(body: unknown, field: string): string | null {
  const value = (body as Record<string, unknown> | null)?.[field];
  return typeof value === 'string' && value.trim() !== '' ? value.trim() : null;
}

export function optionalNumber(body: unknown, field: string, fallback: number): number {
  const value = (body as Record<string, unknown> | null)?.[field];
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}
