import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The backend runs on :8080 and allow-lists http://localhost:5173 for CORS, so the dev server
// talks to it directly. VITE_API_BASE_URL overrides the target for other environments.
export default defineConfig(({ mode }) => {
  // jsdom cannot render canvas; swapping in a no-op stub keeps component tests green without
  // affecting dev builds or production bundles, which always use the real library.
  const testing = mode === 'test' || Boolean(process.env.VITEST);

  return {
    plugins: [react()],
    // sockjs-client (used for the STOMP fallback transport) references the CommonJS `global`
    // object, which browsers do not define. Without this shim the app throws at import time
    // and React never mounts.
    define: {
      global: 'globalThis',
    },
    resolve: testing
      ? {
          alias: {
            'lightweight-charts': fileURLToPath(
              new URL('./src/test/lightweightChartsMock.js', import.meta.url),
            ),
          },
        }
      : undefined,
    server: {
      port: 5173,
      strictPort: true,
    },
    preview: {
      port: 5173,
      strictPort: true,
    },
    test: {
      environment: 'jsdom',
      globals: true,
      setupFiles: './src/test/setup.js',
      css: false,
    },
  };
});
