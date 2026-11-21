import { expect, test, type Page } from '@playwright/test';
import { expectAccessible, newChangeSet, signIn, untilGateways, type Session } from './support';

// One run = one set of uniquely named objects, created by an editor, published by a publisher,
// checked on both gateways, then rolled back. Runs against the Compose stack, no mocks.
test.describe.configure({ mode: 'serial' });

const run = Date.now().toString(36);
const ids = { upstream: `e2e-up-${run}`, service: `e2e-svc-${run}`, consumer: `e2e-c-${run}`, route: `e2e-${run}` };
const ALLOW = '10.250.0.0/24';
let changeSetId = '';
let apiKey = '';
let publishedSeq = 0;
const sessions: Session[] = [];

async function session(browser: Parameters<typeof signIn>[0], role: Parameters<typeof signIn>[1]) {
  const s = await signIn(browser, role);
  sessions.push(s);
  return s;
}

async function stageNew(page: Page, slug: string, id: string, fill: () => Promise<void>, noun: string) {
  await page.goto(`/objects/${slug}/new`);
  await page.getByLabel(/^ID \*/).fill(id);
  await fill();
  await page.getByRole('button', { name: `Stage ${noun}` }).click();
  await page.waitForURL(`**/objects/${slug}/${id}`);
  await expect(page.getByText(/already stages a change to this object/)).toBeVisible();
}

test.afterAll(async () => {
  for (const s of sessions) {
    await s.context.close();
  }
});

test('console is served with a strict CSP and no inline script', async ({ request }) => {
  const r = await request.get('/objects/routes');
  expect(r.status()).toBe(200);
  const csp = r.headers()['content-security-policy'] ?? '';
  expect(csp).toContain("script-src 'self'");
  expect(csp).toContain("style-src 'self'");
  expect(csp).toContain("frame-ancestors 'none'");
  expect(csp).toContain("require-trusted-types-for 'script'");
  expect(csp).not.toContain('unsafe-inline');
  expect(csp).not.toContain('unsafe-eval');
  const html = await r.text();
  expect(html).not.toMatch(/<script(?![^>]*\bsrc=)[^>]*>/);
  expect(html).not.toMatch(/\sstyle=/);
});

test('admin allows the stack network for upstreams (settings)', async ({ browser }) => {
  const { page } = await session(browser, 'admin');
  await page.goto('/settings');
  const cidrs = page.getByLabel('Upstream allow CIDRs');
  await expect(cidrs).toBeVisible();
  await expectAccessible(page, 'settings');
  if ((await cidrs.inputValue()).includes(ALLOW)) {
    return;
  }
  await newChangeSet(page, `e2e ${run}: allow the Compose network`);
  await page.goto('/settings');
  await page.getByLabel('Upstream allow CIDRs').fill(ALLOW);
  await page.getByRole('button', { name: 'Stage settings' }).click();
  await page.getByRole('link', { name: 'Review the change set' }).click();
  await page.getByRole('button', { name: 'Review and publish…' }).click();
  const dialog = page.getByRole('dialog', { name: 'Publish change set' });
  await dialog.getByLabel(/I have reviewed these 1 changes/).check();
  await dialog.getByRole('button', { name: 'Publish' }).click();
  await expect(page.getByText(/Published as #\d+/)).toBeVisible();
});

test('editor stages an upstream, service, consumer with an API key, and a route with schema-driven plugins', async ({ browser }) => {
  const { page } = await session(browser, 'editor');
  await expect(page.locator('.who .badge')).toHaveText('editor');
  await expectAccessible(page, 'gateways');
  changeSetId = await newChangeSet(page, `e2e ${run}: echo route`);

  await stageNew(page, 'upstreams', ids.upstream, async () => {
    await page.getByRole('button', { name: 'Add target' }).click();
    await page.getByLabel(/^Target 1 host/).fill('echo');
    await page.getByLabel(/^Target 1 port/).fill('8080');
  }, 'upstream');

  await stageNew(page, 'services', ids.service, async () => {
    await page.getByLabel(/^Upstream \*/).fill(ids.upstream);
  }, 'service');

  await stageNew(page, 'consumers', ids.consumer, async () => {
    await page.getByLabel('Username').fill(`e2e ${run}`);
  }, 'consumer');
  await page.getByRole('button', { name: 'Generate API key' }).click();
  const keyDialog = page.getByRole('dialog', { name: 'New API key' });
  await expect(keyDialog.getByText(/cannot be shown again/)).toBeVisible();
  apiKey = await keyDialog.getByLabel(`API key for ${ids.consumer}`).inputValue();
  expect(apiKey).toMatch(/^[0-9a-f]{16}\.\S{20,}$/);
  await keyDialog.getByRole('button', { name: 'Done' }).click();
  await expect(page.getByText(apiKey)).toHaveCount(0);

  await page.goto('/objects/routes/new');
  await page.getByLabel(/^ID \*/).fill(ids.route);
  await page.getByLabel(/^Path \*/).fill(`/${ids.route}`);
  await page.getByLabel(/^Service \*/).fill(ids.service);
  await page.getByRole('button', { name: 'Add plugin' }).click();
  await page.getByLabel(/^Plugin 1 name/).selectOption('key-auth');
  await page.getByRole('button', { name: 'Add plugin' }).click();
  await page.getByLabel(/^Plugin 2 name/).selectOption('rate-limit');
  const rl = page.getByRole('group', { name: 'Plugin 2 configuration' });
  const limit = rl.getByLabel(/^limit/);
  await limit.fill('0');
  // Client-side validation mirrors the schema (minimum 1); the server stays authoritative.
  await expect(limit).toHaveAttribute('aria-invalid', 'true');
  await limit.fill('1000');
  await expect(limit).not.toHaveAttribute('aria-invalid', 'true');
  await rl.getByLabel(/^key/).fill('consumer');
  await expectAccessible(page, 'route editor with plugin forms');
  await page.getByRole('button', { name: 'Stage route' }).click();
  await page.waitForURL(`**/objects/routes/${ids.route}`);

  await page.goto(`/changesets/${changeSetId}`);
  await expect(page.getByRole('row')).toHaveCount(6);
  await page.getByRole('button', { name: 'Validate' }).click();
  await expect(page.getByText('Valid: the gateway compiler accepts the result.')).toBeVisible();
  await expect(page.getByRole('button', { name: /publish/i })).toHaveCount(0);
  await expectAccessible(page, 'change set');
  // The diff never shows the stored key hash.
  await expect(page.getByText('sha256$')).toHaveCount(0);
});

test('viewer sees everything read-only', async ({ browser }) => {
  const { page } = await session(browser, 'viewer');
  await page.goto('/objects/routes');
  await expect(page.getByRole('heading', { name: 'Routes' })).toBeVisible();
  await expect(page.getByRole('link', { name: /new route/i })).toHaveCount(0);
  await page.goto(`/changesets/${changeSetId}`);
  await expect(page.getByRole('heading', { name: `e2e ${run}: echo route` })).toBeVisible();
  await expect(page.getByRole('button', { name: /validate|publish|discard|unstage/i })).toHaveCount(0);
  await expect(page.getByRole('link', { name: 'Audit' })).toHaveCount(0);
});

test('a server field error from staging is shown on the field', async ({ browser }) => {
  const { page } = await session(browser, 'editor');
  await page.goto(`/changesets/${changeSetId}`);
  await page.getByRole('button', { name: 'Stage edits into this change set' }).click();
  await page.goto('/objects/routes/new');
  await page.getByLabel(/^ID \*/).fill(`bad-${run}`);
  await page.getByLabel(/^Path \*/).fill('/bad');
  await page.getByLabel(/^Service \*/).fill(ids.service);
  await page.getByText('Other fields (JSON)').click();
  const other = page.getByLabel(/^Other fields/);
  await other.fill('{"bogus": true}');
  await page.getByRole('button', { name: 'Stage route' }).click();
  // The control plane names the field; the console shows it on the control that holds it.
  await expect(other).toHaveAttribute('aria-invalid', 'true');
  await expect(other).toHaveAccessibleDescription(/bogus: unknown field/);
  await expect(page.getByRole('alert').filter({ hasText: 'to fix' })).toBeVisible();
  await expectAccessible(page, 'route editor with errors');
  // Nothing was staged.
  await page.goto(`/changesets/${changeSetId}`);
  await expect(page.getByRole('row')).toHaveCount(6);
});

test('publisher reviews the diff, confirms, and every gateway serves the change within 2 s', async ({ browser }) => {
  const { page } = await session(browser, 'publisher');
  await page.goto(`/changesets/${changeSetId}`);
  await page.getByRole('button', { name: 'Review and publish…' }).click();
  const dialog = page.getByRole('dialog', { name: 'Publish change set' });
  await expect(dialog.getByRole('heading', { name: new RegExp(`routes/${ids.route}`) })).toBeVisible();
  await expectAccessible(page, 'publish dialog');
  const publish = dialog.getByRole('button', { name: 'Publish' });
  await expect(publish).toBeDisabled();
  await dialog.getByLabel(/I have reviewed these 5 changes/).check();
  const t0 = Date.now();
  await publish.click();
  await expect(page.getByText(/Published as #\d+/)).toBeVisible();
  publishedSeq = Number(/\/history\/(\d+)/.exec(page.url())?.[1]);
  await untilGateways(`/${ids.route}`, 401);
  await untilGateways(`/${ids.route}`, 200, { 'x-api-key': apiKey });
  const ms = Date.now() - t0;
  console.log(`E2E-PROPAGATION publish-click-to-all-gateways-ms=${ms}`);
  expect(ms).toBeLessThanOrEqual(2_000);
  await page.goto('/');
  await expect(page.getByText(/2 gateways reporting.*all up to date/)).toBeVisible({ timeout: 15_000 });
});

test('publisher rolls the change back and the gateways drop the route', async ({ browser }) => {
  const { page } = await session(browser, 'publisher');
  await page.goto(`/history/${publishedSeq}`);
  await page.getByRole('button', { name: 'Roll back…' }).click();
  const dialog = page.getByRole('dialog', { name: `Roll back #${publishedSeq}?` });
  await dialog.getByRole('button', { name: 'Publish rollback' }).click();
  await expect(page.getByText(/Published as #\d+/)).toBeVisible();
  await expect(page.getByText(`Rolls back #${publishedSeq}.`)).toBeVisible();
  await untilGateways(`/${ids.route}`, 404);
});

test('admin sees the audit trail and no page reported a CSP violation', async ({ browser }) => {
  const { page } = await session(browser, 'admin');
  await page.goto('/audit');
  await expect(page.getByRole('cell', { name: /rollback/ }).first()).toBeVisible();
  await expectAccessible(page, 'audit');
  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible();
  for (const s of sessions) {
    expect(await s.violations()).toEqual([]);
  }
});
