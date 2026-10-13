import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vitest/config';

/**
 * Pure frontend test harness for the console.
 *
 * Vitest runs the component and unit suites in a jsdom environment (there is no
 * Node server in the console), resolves the same `@/*` -> `./src/*` alias the app
 * uses in `tsconfig.json`, and installs the Testing Library matchers/polyfills
 * from `./src/test/setup.ts`.
 */
export default defineConfig({
  // The app's `tsconfig.json` sets `jsx: "preserve"` for Next's compiler; Vitest
  // transforms TSX itself, so opt into the automatic React 17+ JSX runtime.
  esbuild: {
    jsx: 'automatic',
    jsxImportSource: 'react',
  },
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  test: {
    environment: 'jsdom',
    globals: false,
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/test/**/*.test.{ts,tsx}'],
    css: false,
  },
});
