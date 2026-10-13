import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

// Unmount every tree between tests so the jsdom document never leaks state
// across suites (Testing Library's auto-cleanup only fires when Vitest globals
// are enabled, which this harness deliberately keeps off).
afterEach(() => {
  cleanup();
});

// --- DOM polyfills ---------------------------------------------------------
// Fill the small gaps between jsdom/Node and the browser APIs the console's
// components expect; each shim is installed only when the real one is absent.

if (typeof globalThis.ResizeObserver === 'undefined') {
  class ResizeObserverStub implements ResizeObserver {
    observe(): void {}
    unobserve(): void {}
    disconnect(): void {}
  }
  globalThis.ResizeObserver = ResizeObserverStub as unknown as typeof ResizeObserver;
}

if (typeof window !== 'undefined' && typeof window.matchMedia === 'undefined') {
  window.matchMedia = ((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  })) as unknown as typeof window.matchMedia;
}

// Node 26 ships an experimental `localStorage` global that resolves to
// `undefined` unless `--localstorage-file` is passed, and it shadows the
// jsdom implementation. Install a faithful in-memory `Storage` instead so the
// client's token persistence can be exercised deterministically.
if (typeof globalThis.localStorage === 'undefined') {
  const store = new Map<string, string>();
  const memoryStorage: Storage = {
    get length() {
      return store.size;
    },
    clear() {
      store.clear();
    },
    getItem(key: string) {
      return store.has(key) ? (store.get(key) as string) : null;
    },
    key(index: number) {
      return Array.from(store.keys())[index] ?? null;
    },
    removeItem(key: string) {
      store.delete(key);
    },
    setItem(key: string, value: string) {
      store.set(key, String(value));
    },
  };
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    writable: true,
    value: memoryStorage,
  });
}

// `AbortController`/`AbortSignal` are provided natively by both jsdom and the
// Node runtime the harness runs on, so no shim is required for them.
