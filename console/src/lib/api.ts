import type {
  App,
  AppDeployment,
  AppDeploymentList,
  AppList,
  ApiErrorBody,
  FileList,
  Health,
  Job,
  JobList,
  NodeList,
  Service,
  ServiceList,
  Whoami,
} from './types';

/**
 * Browser-side client for the Automately public REST API.
 *
 * The console is served by the ingress on the same origin as the API, and the
 * app manifest rewrites `/v1/*` to the local node's API (zero CORS). Every
 * request therefore targets a same-origin path — no base URL is needed.
 *
 * The bearer token is kept in `localStorage`. When any request comes back `401`
 * the stored token is cleared and every registered {@link onUnauthorized}
 * listener fires, which lets the dashboard fall back to the login prompt.
 */

const TOKEN_KEY = 'automately.console.token';

/** Thrown for any non-2xx response from the API. */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string | number | undefined;

  constructor(status: number, message: string, code?: string | number) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
  }
}

/** Thrown specifically on `401`, after the token has been cleared. */
export class UnauthorizedError extends ApiError {
  constructor(message = 'Your token is missing, invalid, or expired.') {
    super(401, message);
    this.name = 'UnauthorizedError';
  }
}

// ---------------------------------------------------------------------------
// Token storage
// ---------------------------------------------------------------------------

let memoryToken: string | null = null;

/** Reads the bearer token, preferring an in-memory value (for non-DOM tests). */
export function getToken(): string | null {
  if (memoryToken !== null) return memoryToken;
  if (typeof window === 'undefined') return null;
  try {
    return window.localStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

/** Persists a bearer token for later requests. */
export function setToken(token: string): void {
  memoryToken = token;
  if (typeof window === 'undefined') return;
  try {
    window.localStorage.setItem(TOKEN_KEY, token);
  } catch {
    /* storage may be unavailable (private mode); the in-memory copy still works */
  }
}

/** Forgets the current bearer token. */
export function clearToken(): void {
  memoryToken = null;
  if (typeof window === 'undefined') return;
  try {
    window.localStorage.removeItem(TOKEN_KEY);
  } catch {
    /* ignore */
  }
}

// ---------------------------------------------------------------------------
// Unauthorized listeners
// ---------------------------------------------------------------------------

type UnauthorizedListener = () => void;
const unauthorizedListeners = new Set<UnauthorizedListener>();

/** Subscribes to `401` events. Returns an unsubscribe function. */
export function onUnauthorized(listener: UnauthorizedListener): () => void {
  unauthorizedListeners.add(listener);
  return () => unauthorizedListeners.delete(listener);
}

function emitUnauthorized(): void {
  for (const listener of unauthorizedListeners) {
    try {
      listener();
    } catch {
      /* a misbehaving listener must not mask the request failure */
    }
  }
}

// ---------------------------------------------------------------------------
// Core request helper
// ---------------------------------------------------------------------------

interface RequestOptions {
  method?: string;
  body?: unknown;
  /** Send the bearer token (default true; `/health` opts out). */
  auth?: boolean;
  signal?: AbortSignal;
}

async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, auth = true, signal } = options;

  const headers: Record<string, string> = { Accept: 'application/json' };
  if (body !== undefined) headers['Content-Type'] = 'application/json';

  const token = getToken();
  if (auth && token) headers.Authorization = `Bearer ${token}`;

  let response: Response;
  try {
    response = await fetch(path, {
      method,
      headers,
      signal,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch (cause) {
    // Network-level failure (offline, DNS, CORS, aborted).
    if (cause instanceof DOMException && cause.name === 'AbortError') throw cause;
    throw new ApiError(0, 'Could not reach the Automately API.', undefined);
  }

  if (response.status === 401) {
    clearToken();
    const message = await extractErrorMessage(response);
    emitUnauthorized();
    throw new UnauthorizedError(message);
  }

  if (!response.ok) {
    throw new ApiError(response.status, await extractErrorMessage(response));
  }

  if (response.status === 204) return undefined as T;

  const contentType = response.headers.get('content-type') ?? '';
  if (!contentType.includes('application/json')) {
    // 202/204-style acknowledgements or unexpected bodies: nothing to parse.
    const text = await response.text();
    if (!text) return undefined as T;
    try {
      return JSON.parse(text) as T;
    } catch {
      return text as unknown as T;
    }
  }

  return (await response.json()) as T;
}

async function extractErrorMessage(response: Response): Promise<string> {
  try {
    const contentType = response.headers.get('content-type') ?? '';
    if (contentType.includes('application/json')) {
      const body = (await response.json()) as Partial<ApiErrorBody>;
      if (body?.error?.message) return body.error.message;
    } else {
      const text = await response.text();
      if (text) return text;
    }
  } catch {
    /* fall through to the generic message */
  }
  return `Request failed with status ${response.status}.`;
}

// ---------------------------------------------------------------------------
// Typed API surface
// ---------------------------------------------------------------------------

export const api = {
  /** `GET /v1/whoami` — the token's identity and scopes. */
  whoami(signal?: AbortSignal): Promise<Whoami> {
    return request<Whoami>('/v1/whoami', { signal });
  },

  /** `GET /v1/nodes` — live nodes with capacity and load. */
  getNodes(signal?: AbortSignal): Promise<NodeList> {
    return request<NodeList>('/v1/nodes', { signal });
  },

  /** `GET /health` — liveness; no token required. */
  getHealth(signal?: AbortSignal): Promise<Health> {
    return request<Health>('/health', { auth: false, signal });
  },

  /** `GET /v1/jobs` — the tenant's jobs, newest first. */
  getJobs(signal?: AbortSignal): Promise<JobList> {
    return request<JobList>('/v1/jobs', { signal });
  },

  /** `POST /v1/jobs/{id}/cancel` — request a graceful stop. */
  cancelJob(id: string, signal?: AbortSignal): Promise<Job> {
    return request<Job>(`/v1/jobs/${encodeURIComponent(id)}/cancel`, {
      method: 'POST',
      signal,
    });
  },

  /** `GET /v1/services` — the tenant's supervised services. */
  getServices(signal?: AbortSignal): Promise<ServiceList> {
    return request<ServiceList>('/v1/services', { signal });
  },

  /** `POST /v1/services/{name}/restart` — restart a service's current run. */
  restartService(name: string, signal?: AbortSignal): Promise<Service> {
    return request<Service>(`/v1/services/${encodeURIComponent(name)}/restart`, {
      method: 'POST',
      signal,
    });
  },

  /** `POST /v1/services/{name}/stop` — stop (disable) a service. */
  stopService(name: string, signal?: AbortSignal): Promise<Service> {
    return request<Service>(`/v1/services/${encodeURIComponent(name)}/stop`, {
      method: 'POST',
      signal,
    });
  },

  /** `GET /v1/apps` — the tenant's apps. */
  getApps(signal?: AbortSignal): Promise<AppList> {
    return request<AppList>('/v1/apps', { signal });
  },

  /** `GET /v1/apps/{name}` — one app and which deployment is live. */
  getApp(name: string, signal?: AbortSignal): Promise<App> {
    return request<App>(`/v1/apps/${encodeURIComponent(name)}`, { signal });
  },

  /** `GET /v1/apps/{name}/deployments` — an app's deployments, oldest first. */
  getDeployments(name: string, signal?: AbortSignal): Promise<AppDeploymentList> {
    return request<AppDeploymentList>(`/v1/apps/${encodeURIComponent(name)}/deployments`, {
      signal,
    });
  },

  /** `POST /v1/apps/{name}/promote` — point an app at a deployment. */
  promoteApp(name: string, deployId: string, signal?: AbortSignal): Promise<App> {
    return request<App>(`/v1/apps/${encodeURIComponent(name)}/promote`, {
      method: 'POST',
      body: { deploymentId: deployId },
      signal,
    });
  },

  /** `POST /v1/apps/{name}/rollback` — back to the previously promoted deployment. */
  rollbackApp(name: string, signal?: AbortSignal): Promise<App> {
    return request<App>(`/v1/apps/${encodeURIComponent(name)}/rollback`, {
      method: 'POST',
      signal,
    });
  },

  /** `GET /v1/files` — the tenant's files, by path. */
  getFiles(signal?: AbortSignal): Promise<FileList> {
    return request<FileList>('/v1/files', { signal });
  },
};

export type { AppDeployment };
