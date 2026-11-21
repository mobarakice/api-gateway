import type { Problem } from './types';

/** An Admin API failure with its RFC 9457 body (or a synthesized one for network errors). */
export class ApiError extends Error {
  readonly problem: Problem;

  constructor(problem: Problem) {
    super(problem.detail ?? problem.title);
    this.name = 'ApiError';
    this.problem = problem;
  }

  get status(): number {
    return this.problem.status;
  }
}

export interface RequestOptions {
  body?: unknown;
  ifMatch?: string | undefined;
  query?: Record<string, string | number | undefined>;
  signal?: AbortSignal | undefined;
}

export interface Response<T> {
  data: T;
  etag: string | undefined;
}

export type TokenSource = () => string | undefined;

function isProblem(v: unknown): v is Problem {
  return typeof v === 'object' && v !== null && typeof (v as { title?: unknown }).title === 'string'
    && typeof (v as { status?: unknown }).status === 'number';
}

/** Thin fetch wrapper: bearer token from memory, JSON in and out, problem details on failure. */
export class AdminClient {
  private readonly base: string;
  private readonly token: TokenSource;
  private readonly onUnauthorized: () => void;

  constructor(apiBase: string, token: TokenSource, onUnauthorized: () => void) {
    this.base = `${apiBase}/admin/v1/`;
    this.token = token;
    this.onUnauthorized = onUnauthorized;
  }

  url(path: string, query?: RequestOptions['query']): string {
    const u = new URL(this.base + path.replace(/^\/+/, ''), window.location.origin);
    for (const [k, v] of Object.entries(query ?? {})) {
      if (v !== undefined) {
        u.searchParams.set(k, String(v));
      }
    }
    return u.toString();
  }

  async request<T>(method: string, path: string, opts: RequestOptions = {}): Promise<Response<T>> {
    const headers = new Headers({ accept: 'application/json' });
    const token = this.token();
    if (token) {
      headers.set('authorization', `Bearer ${token}`);
    }
    if (opts.ifMatch) {
      headers.set('if-match', opts.ifMatch);
    }
    let body: string | undefined;
    if (opts.body !== undefined) {
      headers.set('content-type', 'application/json');
      body = JSON.stringify(opts.body);
    }
    let r: globalThis.Response;
    try {
      r = await fetch(this.url(path, opts.query), {
        method, headers, body, credentials: 'omit', cache: 'no-store', redirect: 'error', signal: opts.signal,
      });
    } catch (e) {
      if (e instanceof DOMException && e.name === 'AbortError') {
        throw e;
      }
      throw new ApiError({ title: 'Admin API unreachable', status: 0, detail: 'Check your connection and try again.' });
    }
    const etag = r.headers.get('etag') ?? undefined;
    const text = await r.text();
    let parsed: unknown = undefined;
    if (text !== '') {
      try {
        parsed = JSON.parse(text);
      } catch {
        parsed = undefined;
      }
    }
    if (!r.ok) {
      if (r.status === 401) {
        this.onUnauthorized();
      }
      const requestId = r.headers.get('x-request-id') ?? undefined;
      throw new ApiError(isProblem(parsed) ? parsed
        : { title: r.statusText || 'Request failed', status: r.status, ...(requestId ? { requestId } : {}) });
    }
    return { data: parsed as T, etag };
  }

  get<T>(path: string, query?: RequestOptions['query'], signal?: AbortSignal): Promise<Response<T>> {
    return this.request<T>('GET', path, { ...(query ? { query } : {}), signal });
  }
}
