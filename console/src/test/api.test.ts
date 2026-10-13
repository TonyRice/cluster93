import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  ApiError,
  UnauthorizedError,
  api,
  clearToken,
  getTargetTenant,
  getToken,
  onUnauthorized,
  setTargetTenant,
  setToken,
} from '@/lib/api';

/** Builds a `Response` with a JSON body, mirroring the API's error shape. */
function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

const fetchMock = vi.fn();

/** Unsubscribers registered during a test, torn down afterwards. */
const unsubscribers: Array<() => void> = [];

function track(listener: () => void): () => void {
  const unsubscribe = onUnauthorized(listener);
  unsubscribers.push(unsubscribe);
  return unsubscribe;
}

beforeEach(() => {
  clearToken();
  setTargetTenant(null);
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  for (const unsubscribe of unsubscribers.splice(0)) unsubscribe();
  vi.unstubAllGlobals();
  clearToken();
  setTargetTenant(null);
});

describe('token management', () => {
  it('starts with no token', () => {
    expect(getToken()).toBeNull();
  });

  it('stores, reads, and clears the bearer token', () => {
    setToken('tok-123');

    expect(getToken()).toBe('tok-123');
    expect(window.localStorage.getItem('cluster93.console.token')).toBe('tok-123');

    clearToken();

    expect(getToken()).toBeNull();
    expect(window.localStorage.getItem('cluster93.console.token')).toBeNull();
  });

  it('sends the stored token as a bearer credential', async () => {
    setToken('tok-123');
    fetchMock.mockResolvedValueOnce(jsonResponse({ nodes: [] }));

    await api.getNodes();

    const [, init] = fetchMock.mock.calls[0];
    expect(init.headers.Authorization).toBe('Bearer tok-123');
  });

  it('omits authentication for /health', async () => {
    setToken('tok-123');
    fetchMock.mockResolvedValueOnce(jsonResponse({ status: 'UP', node: 'node-a' }));

    await api.getHealth();

    const [path, init] = fetchMock.mock.calls[0];
    expect(path).toBe('/health');
    expect(init.headers.Authorization).toBeUndefined();
  });
});

describe('target tenant scoping', () => {
  it('defaults to no target tenant', () => {
    expect(getTargetTenant()).toBeNull();
  });

  it("attaches X-Cluster93-Tenant for subsequent requests after setTargetTenant('alpha')", async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse({ nodes: [] }));

    setTargetTenant('alpha');
    await api.getNodes();

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [path, init] = fetchMock.mock.calls[0];
    expect(path).toBe('/v1/nodes');
    expect(init.headers['X-Cluster93-Tenant']).toBe('alpha');
  });

  it('sends no tenant header when the target is cleared', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse({ nodes: [] }));

    setTargetTenant('alpha');
    setTargetTenant(null);
    await api.getNodes();

    const [, init] = fetchMock.mock.calls[0];
    expect(init.headers['X-Cluster93-Tenant']).toBeUndefined();
  });
});

describe('unauthorized handling', () => {
  it('clears the token, notifies listeners, and rejects on a 401', async () => {
    const listener = vi.fn();
    track(listener);
    setToken('expired');

    fetchMock.mockResolvedValueOnce(
      jsonResponse({ error: { code: 401, message: 'Token expired.' } }, 401),
    );

    await expect(api.getNodes()).rejects.toBeInstanceOf(UnauthorizedError);

    expect(listener).toHaveBeenCalledTimes(1);
    expect(getToken()).toBeNull();
    expect(window.localStorage.getItem('cluster93.console.token')).toBeNull();
  });

  it('stops notifying once the listener unsubscribes', async () => {
    const listener = vi.fn();
    track(listener)();
    setToken('expired');

    fetchMock.mockResolvedValueOnce(jsonResponse({ error: { code: 401, message: 'no' } }, 401));

    await expect(api.getNodes()).rejects.toBeInstanceOf(UnauthorizedError);
    expect(listener).not.toHaveBeenCalled();
  });

  it('does not fire the unauthorized listeners for other failures', async () => {
    const listener = vi.fn();
    track(listener);
    setToken('valid');

    fetchMock.mockResolvedValueOnce(jsonResponse({ error: { code: 500, message: 'boom' } }, 500));

    await expect(api.getNodes()).rejects.toBeInstanceOf(ApiError);
    expect(listener).not.toHaveBeenCalled();
    expect(getToken()).toBe('valid');
  });
});

describe('error mapping', () => {
  it('surfaces the API error message and status', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse({ error: { code: 500, message: 'boom' } }, 500));

    await expect(api.getNodes()).rejects.toMatchObject({ status: 500, message: 'boom' });
  });

  it('maps a network failure to a status-0 ApiError', async () => {
    fetchMock.mockRejectedValueOnce(new TypeError('Failed to fetch'));

    await expect(api.getNodes()).rejects.toMatchObject({
      status: 0,
      message: 'Could not reach the Cluster93 API.',
    });
  });
});
