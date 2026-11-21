import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

// Production output has no inline scripts or styles (strict CSP, see deployment/docker/console/sws.toml).
// The dev server is for local work only: React Fast Refresh injects an inline preamble there.
export default defineConfig({
  plugins: [react()],
  build: {
    target: 'es2022',
    sourcemap: false,
    // The preload polyfill would be inlined into the entry; modern browsers do not need it.
    modulePreload: { polyfill: false },
    assetsInlineLimit: 0,
  },
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      // Same-origin Admin API in dev: the local control plane (Compose) uses a dev CA.
      '/admin': { target: 'https://localhost:9443', changeOrigin: true, secure: false },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test-setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    css: false,
  },
});
