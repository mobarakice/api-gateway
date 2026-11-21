import { afterEach, describe, expect, it, vi } from 'vitest';
import { AdminClient, ApiError } from './client';

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('AdminClient', () => {
  it('sends the in-memory bearer token, If-Match and JSON, never cookies', async () => {
    const f = vi.fn(() => Promise.resolve(new Response('{"ok":true}', { status: 200, headers: { etag: '"7"' } })));
    vi.stubGlobal('fetch', f);
    const c = new AdminClient('https://cp.test', () => 'tok', () => undefined);
    const r = await c.request<{ ok: boolean }>('PUT', 'routes/a', { body: { spec: {} }, ifMatch: '"6"',
      query: { changeset: 'cs-1' } });
    expect(r).toEqual({ data: { ok: true }, etag: '"7"' });
    const [url, init] = f.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe('https://cp.test/admin/v1/routes/a?changeset=cs-1');
    const h = new Headers(init.headers);
    expect(h.get('authorization')).toBe('Bearer tok');
    expect(h.get('if-match')).toBe('"6"');
    expect(init.credentials).toBe('omit');
    expect(init.body).toBe('{"spec":{}}');
  });

  it('turns problem details into ApiError and signals 401', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(new Response(JSON.stringify({ title: 'Precondition failed', status: 412,
      conflicts: [{ key: 'routes/a', expectedRevision: 3, actualRevision: 5 }] }), { status: 412 }))));
    const expired = vi.fn();
    const c = new AdminClient('', () => undefined, expired);
    await expect(c.request('POST', 'changesets/x/publish')).rejects.toMatchObject({ status: 412,
      problem: { conflicts: [{ key: 'routes/a' }] } });
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(new Response('', { status: 401, statusText: 'Unauthorized' }))));
    await expect(c.get('me')).rejects.toBeInstanceOf(ApiError);
    expect(expired).toHaveBeenCalledOnce();
  });

  it('reports network failures without leaking details', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new TypeError('Failed to fetch https://internal'))));
    const c = new AdminClient('', () => undefined, () => undefined);
    await expect(c.get('me')).rejects.toMatchObject({ status: 0, problem: { title: 'Admin API unreachable' } });
  });
});
