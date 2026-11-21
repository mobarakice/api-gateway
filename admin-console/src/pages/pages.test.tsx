import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fakeApi, renderAt } from '../test-utils';
import { ChangeSetPage } from './ChangeSets';
import { ObjectPage } from './ObjectPage';
import { ObjectsPage } from './ObjectsPage';

afterEach(() => {
  vi.unstubAllGlobals();
  window.sessionStorage.clear();
});

const route = { key: 'routes/a', revision: 7, etag: '"7"', id: 'a', kind: 'Route', schemaVersion: 1,
  spec: { path: '/a', serviceId: 's' } };

const changeSet = { id: 'cs-1', title: 'Add a route', createdBy: 'ed', createdAt: '2026-11-21T10:00:00Z',
  updatedAt: '2026-11-21T10:00:00Z', ops: 1, etag: '"12"',
  items: [{ key: 'routes/b', action: 'PUT', baseRevision: 0, stagedBy: 'ed',
    value: { id: 'b', kind: 'Route', spec: { path: '/b', serviceId: 's' } } }] };

describe('role-aware UI', () => {
  it('hides writes from viewers (the API still enforces them)', async () => {
    fakeApi('viewer', { 'GET routes': { items: [route] }, 'GET routes/a': route });
    renderAt('/objects/routes', '/objects/:slug', <ObjectsPage />);
    expect(await screen.findByRole('link', { name: 'a' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /new route/i })).not.toBeInTheDocument();
  });

  it('shows writes to editors', async () => {
    fakeApi('editor', { 'GET routes': { items: [route] } });
    renderAt('/objects/routes', '/objects/:slug', <ObjectsPage />);
    expect(await screen.findByRole('link', { name: /new route/i })).toBeInTheDocument();
  });

  it('a viewer sees a read-only editor', async () => {
    fakeApi('viewer', { 'GET routes/a': route });
    renderAt('/objects/routes/a', '/objects/:slug/:id', <ObjectPage />);
    expect(await screen.findByText(/read only/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/^Path \*/)).toBeDisabled();
    expect(screen.queryByRole('button', { name: /stage route/i })).not.toBeInTheDocument();
  });
});

describe('staging', () => {
  it('sends If-Match with the live ETag and shows server field errors inline', async () => {
    window.sessionStorage.setItem('apigw.activeChangeSet', 'cs-1');
    const api = fakeApi('editor', {
      'GET routes/a': route, 'GET changesets/cs-1': { ...changeSet, items: [] },
      'PUT routes/a': { status: 422, body: { title: 'Invalid', status: 422,
        errors: [{ path: 'routes/a.spec.path', message: 'must start with /' }] } },
    });
    const user = userEvent.setup();
    renderAt('/objects/routes/a', '/objects/:slug/:id', <ObjectPage />);
    const path = await screen.findByLabelText(/^Path \*/);
    await user.clear(path);
    await user.type(path, 'nope');
    await user.click(screen.getByRole('button', { name: /stage route/i }));
    await waitFor(() => { expect(path).toHaveAccessibleDescription(/must start with \//); });
    const put = api.calls.find((c) => c.method === 'PUT');
    expect(put?.headers.get('if-match')).toBe('"7"');
    expect(put?.query.get('changeset')).toBe('cs-1');
    expect(put?.body).toEqual({ spec: { path: 'nope', serviceId: 's' } });
  });
});

describe('publishing', () => {
  it('always shows the diff and needs explicit confirmation', async () => {
    const api = fakeApi('publisher', {
      'GET changesets/cs-1': changeSet,
      'GET changesets/cs-1/diff': { items: [{ key: 'routes/b', action: 'PUT', baseRevision: 0, liveRevision: 0,
        stale: false, before: null, after: { id: 'b', spec: { path: '/b' } } }] },
      'POST changesets/cs-1/publish': { seq: 3, revision: 99, changeSetId: 'cs-1' },
    });
    const user = userEvent.setup();
    renderAt('/changesets/cs-1', '/changesets/:id', <ChangeSetPage />);
    await user.click(await screen.findByRole('button', { name: /review and publish/i }));
    const dialog = await screen.findByRole('dialog', { name: /publish change set/i });
    expect(await within(dialog).findByText(/1 added|added/)).toBeInTheDocument();
    const publish = within(dialog).getByRole('button', { name: 'Publish' });
    expect(publish).toBeDisabled();
    await user.click(within(dialog).getByLabelText(/i have reviewed these 1 changes/i));
    await user.click(publish);
    await screen.findByText('elsewhere');
    const post = api.calls.find((c) => c.method === 'POST' && c.path.endsWith('/publish'));
    expect(post?.headers.get('if-match')).toBe('"12"');
  });

  it('editors cannot publish', async () => {
    fakeApi('editor', { 'GET changesets/cs-1': changeSet, 'GET changesets/cs-1/diff': { items: [] } });
    renderAt('/changesets/cs-1', '/changesets/:id', <ChangeSetPage />);
    expect(await screen.findByRole('button', { name: /validate/i })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /publish/i })).not.toBeInTheDocument();
  });
});

describe('credentials', () => {
  it('shows a generated API key once and forgets it when closed', async () => {
    window.sessionStorage.setItem('apigw.activeChangeSet', 'cs-1');
    const consumer = { ...route, key: 'consumers/acme', id: 'acme', kind: 'Consumer', spec: { username: 'acme' } };
    fakeApi('editor', {
      'GET consumers/acme': consumer, 'GET changesets/cs-1': { ...changeSet, items: [] },
      'GET consumers/acme/credentials': { items: [] },
      'POST consumers/acme/credentials': { status: 201, body: { changeSet: { id: 'cs-1', etag: '"13"', ops: 1 },
        op: { key: 'credentials/acme/key-1', action: 'PUT', baseRevision: 0 }, id: 'key-1', keyId: 'abcd',
        apiKey: 'abcd.s3cr3t-value', note: 'store it now' } },
    });
    const user = userEvent.setup();
    renderAt('/objects/consumers/acme', '/objects/:slug/:id', <ObjectPage />);
    await user.click(await screen.findByRole('button', { name: /generate api key/i }));
    const dialog = await screen.findByRole('dialog', { name: /new api key/i });
    expect(within(dialog).getByLabelText(/api key for acme/i)).toHaveValue('abcd.s3cr3t-value');
    await user.click(within(dialog).getByRole('button', { name: 'Done' }));
    await waitFor(() => { expect(screen.queryByDisplayValue('abcd.s3cr3t-value')).not.toBeInTheDocument(); });
    expect(document.body.innerHTML).not.toContain('s3cr3t');
  });
});
