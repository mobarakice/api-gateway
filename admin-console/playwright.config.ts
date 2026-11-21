import { defineConfig, devices } from '@playwright/test';

// End-to-end against the real Compose stack (deployment/docker/compose.yml): console, control plane,
// etcd, Keycloak and two gateways. Nothing is mocked.
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 90_000,
  expect: { timeout: 10_000 },
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: process.env.CONSOLE_URL ?? 'http://localhost:8280',
    // The local stack's Admin API and Keycloak use a generated dev CA.
    ignoreHTTPSErrors: true,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
