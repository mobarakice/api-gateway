import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import type { ReactElement } from 'react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { vi } from 'vitest';
import { AdminClient } from './api/client';
import { ClientContext } from './api/hooks';
import { ActiveChangeSetProvider } from './components/ActiveChangeSet';
import type { Role } from './roles';

export interface Call {
  method: string;
  path: string;
  query: URLSearchParams;
  headers: Headers;
  body: unknown;
}

interface Reply { status?: number; body?: unknown; etag?: string }
type Handler = (c: Call) => Reply | undefined;

/** A fake Admin API behind fetch: routes by "METHOD path", records every call. */
export function fakeApi(role: Role, routes: Record<string, Handler | object>) {
  const calls: Call[] = [];
  const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const u = new URL(input instanceof Request ? input.url : input.toString());
    const path = u.pathname.replace(/^\/admin\/v1\//, '');
    const method = init?.method ?? 'GET';
    const headers = new Headers(init?.headers);
    const body: unknown = typeof init?.body === 'string' ? JSON.parse(init.body) : undefined;
    const call = { method, path, query: u.searchParams, headers, body };
    calls.push(call);
    const all: Record<string, Handler | object> = {
      'GET me': { subject: `${role}@test`, role, roles: [role], permissions: [role] },
      'GET changesets': { items: [] },
      'GET plugins': { items: [] },
      ...routes,
    };
    const h = all[`${method} ${path}`];
    const isResponse = (x: object): x is Reply => 'status' in x && 'body' in x;
    const r: Reply | undefined = typeof h === 'function' ? (h as Handler)(call) : h === undefined ? { status: 404,
      body: { title: 'Not found', status: 404 } } : isResponse(h) ? h : { body: h };
    const status = r?.status ?? 200;
    return Promise.resolve(new Response(r?.body === undefined ? null : JSON.stringify(r.body), {
      status, headers: { 'content-type': status >= 400 ? 'application/problem+json' : 'application/json',
        ...(r?.etag ? { etag: r.etag } : {}) },
    }));
  });
  vi.stubGlobal('fetch', fetchMock);
  return { calls };
}

export function renderAt(path: string, routePattern: string, element: ReactElement) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const client = new AdminClient('', () => 'test-token', () => undefined);
  return render(
    <ClientContext.Provider value={client}>
      <QueryClientProvider client={qc}>
        <ActiveChangeSetProvider>
          <MemoryRouter initialEntries={[path]}>
            <Routes>
              <Route path={routePattern} element={element} />
              <Route path="*" element={<p>elsewhere</p>} />
            </Routes>
          </MemoryRouter>
        </ActiveChangeSetProvider>
      </QueryClientProvider>
    </ClientContext.Provider>,
  );
}
