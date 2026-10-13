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
  Tenant,
  Whoami,
} from './types';

/**
 * A signed-in user, as the API's `User` schema describes it (D-070). `operator`
 * manages the cluster; `disabled` means their tokens all stop working.
 */
export interface User {
  id: string;
  name: string;
  displayName?: string | null;
  email?: string | null;
  operator: boolean;
  disabled: boolean;
  createdAt: number;
  /** Whether the user has a password configured for console login (D-133). */
  hasPassword?: boolean;
}

/** `POST /v1/auth/login` request body. */
export interface LoginRequest {
  username: string;
  password: string;
  /** Optional: the tenant to scope the session to (defaults to the first the user belongs to). */
  tenant?: string;
}

/** `POST /v1/auth/login` response: a personal session token and the user it belongs to. */
export interface LoginResponse {
  token: string;
  admin: boolean;
  tenant: string | null;
  user: User;
}

/** A tenant member, as `GET /v1/members` describes it (D-070). */
export interface Member {
  tenant: string;
  user?: string | null;
  userId?: string;
  roles: string[];
  groups: string[];
  scopes: string[];
  owner: boolean;
  joinedAt?: number;
  invitedBy?: string | null;
}

/** An open invite, as `GET /v1/invites` describes it (D-070); `code` is present only on creation. */
export interface Invite {
  id: string;
  tenant: string;
  roles: string[];
  groups: string[];
  user?: string | null;
  note?: string | null;
  createdBy?: string | null;
  createdAt?: number;
  expiresAt?: number;
  code?: string;
}

/** `POST /v1/invites/redeem` request body. */
export interface RedeemInviteRequest {
  code: string;
  name?: string;
  displayName?: string;
  email?: string;
  password?: string;
}

/**
 * Browser-side client for the Cluster93 public REST API.
 *
 * The console is served by the ingress on the same origin as the API, and the
 * app manifest rewrites `/v1/*` to the local node's API (zero CORS). Every
 * request therefore targets a same-origin path — no base URL is needed.
 *
 * The bearer token is kept in `localStorage`. When any request comes back `401`
 * the stored token is cleared and every registered {@link onUnauthorized}
 * listener fires, which lets the dashboard fall back to the login prompt.
 */

const TOKEN_KEY = 'cluster93.console.token';

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
// Target tenant
// ---------------------------------------------------------------------------

let targetTenant: string | null = null;

/** Scopes subsequent requests to a tenant (the `X-Cluster93-Tenant` header). */
export function setTargetTenant(tenant: string | null): void {
  targetTenant = tenant;
}

/** The tenant currently targeted, or `null` for the operator's cluster view. */
export function getTargetTenant(): string | null {
  return targetTenant;
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
  if (targetTenant) headers['X-Cluster93-Tenant'] = targetTenant;

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
    throw new ApiError(0, 'Could not reach the Cluster93 API.', undefined);
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

/** Strips leading slashes from a VFS path so it can be appended to `/v1/files/`. */
function cleanPath(path: string): string {
  return path.replace(/^\/+/, '').trim();
}

/**
 * Downloads raw file bytes/text from `GET /v1/files/{path}`. Unlike {@link request}
 * this returns the body verbatim (files are not JSON), so it does its own fetch
 * while still sending the bearer token and any target-tenant header.
 */
async function fetchFileContent(path: string, signal?: AbortSignal): Promise<string> {
  const headers: Record<string, string> = { Accept: '*/*' };
  if (targetTenant) headers['X-Cluster93-Tenant'] = targetTenant;
  const token = getToken();
  if (token) headers.Authorization = `Bearer ${token}`;

  let response: Response;
  try {
    response = await fetch(`/v1/files/${cleanPath(path)}`, { method: 'GET', headers, signal });
  } catch (cause) {
    if (cause instanceof DOMException && cause.name === 'AbortError') throw cause;
    throw new ApiError(0, 'Could not reach the Cluster93 API.', undefined);
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

  return await response.text();
}

// ---------------------------------------------------------------------------
// Typed API surface
// ---------------------------------------------------------------------------

export const api = {
  /**
   * `POST /v1/auth/login` — exchange a username and password for a personal
   * session token. Public: sends no bearer credential.
   */
  async login(req: LoginRequest): Promise<LoginResponse> {
    return request<LoginResponse>('/v1/auth/login', { method: 'POST', body: req, auth: false });
  },

  /**
   * `GET /v1/auth/setup-status` — whether the cluster still needs its first
   * operator account. Public: sends no bearer credential. Drives the console's
   * initial setup screen (D-070).
   */
  getSetupStatus(): Promise<{ needsSetup: boolean }> {
    return request<{ needsSetup: boolean }>('/v1/auth/setup-status', { auth: false });
  },

  /**
   * `POST /v1/auth/setup` — create the cluster's first operator account and
   * sign in. Public, and only while no operator exists.
   */
  setupAdmin(data: {
    username: string;
    password: string;
    displayName?: string;
  }): Promise<{ token: string; user: User }> {
    return request<{ token: string; user: User }>('/v1/auth/setup', {
      method: 'POST',
      body: data,
      auth: false,
    });
  },

  /** `PUT /v1/me/password` — set or change your own password (D-133). */
  updatePassword(req: { currentPassword?: string; newPassword: string }): Promise<{ status: string }> {
    return request<{ status: string }>('/v1/me/password', { method: 'PUT', body: req });
  },

  /** `POST /v1/invites/redeem` — redeem an invite for a new user, with a password. */
  redeemInvite(req: RedeemInviteRequest): Promise<LoginResponse> {
    return request<LoginResponse>('/v1/invites/redeem', { method: 'POST', body: req, auth: false });
  },

  /** `GET /v1/members` — the tenant's members. */
  getMembers(signal?: AbortSignal): Promise<{ members: Member[] }> {
    return request<{ members: Member[] }>('/v1/members', { signal });
  },

  /** `GET /v1/invites` — the tenant's open invites. */
  getTenantInvites(signal?: AbortSignal): Promise<{ invites: Invite[] }> {
    return request<{ invites: Invite[] }>('/v1/invites', { signal });
  },

  /** `POST /v1/invites` — invite someone to the current tenant. */
  createTenantInvite(req: { roles: string[]; groups?: string[]; user?: string; note?: string }): Promise<Invite> {
    return request<Invite>('/v1/invites', { method: 'POST', body: req });
  },

  /** `DELETE /v1/invites/{id}` — withdraw a tenant invite. */
  revokeTenantInvite(id: string): Promise<void> {
    return request<void>(`/v1/invites/${encodeURIComponent(id)}`, { method: 'DELETE' });
  },

  /** `GET /v1/users` — every user on the cluster (admin). */
  getUsers(signal?: AbortSignal): Promise<{ users: User[] }> {
    return request<{ users: User[] }>('/v1/users', { signal });
  },

  /** `GET /v1/admin/invites` — open operator invites (admin). */
  getAdminInvites(signal?: AbortSignal): Promise<{ invites: Invite[] }> {
    return request<{ invites: Invite[] }>('/v1/admin/invites', { signal });
  },

  /** `POST /v1/admin/invites` — invite an operator (admin). */
  createAdminInvite(req: { user?: string; note?: string }): Promise<Invite> {
    return request<Invite>('/v1/admin/invites', { method: 'POST', body: req });
  },

  /** `DELETE /v1/admin/invites/{id}` — withdraw an operator invite (admin). */
  revokeAdminInvite(id: string): Promise<void> {
    return request<void>(`/v1/admin/invites/${encodeURIComponent(id)}`, { method: 'DELETE' });
  },

  /** `GET /v1/whoami` — the token's identity and scopes. */
  whoami(signal?: AbortSignal): Promise<Whoami> {
    return request<Whoami>('/v1/whoami', { signal });
  },

  /** `GET /v1/nodes` — live nodes with capacity and load. */
  getNodes(signal?: AbortSignal): Promise<NodeList> {
    return request<NodeList>('/v1/nodes', { signal });
  },

  /** `GET /v1/tenants` — every tenant workspace (admin). */
  getTenants(signal?: AbortSignal): Promise<{ tenants: Tenant[] }> {
    return request<{ tenants: Tenant[] }>('/v1/tenants', { signal });
  },

  /** `POST /v1/tenants` — create a tenant workspace (admin). */
  createTenant(name: string, signal?: AbortSignal): Promise<Tenant> {
    return request<Tenant>('/v1/tenants', { method: 'POST', body: { name }, signal });
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

  /** `GET /v1/files/{path}` — a file's raw contents as text. */
  getFileContent(path: string, signal?: AbortSignal): Promise<string> {
    return fetchFileContent(path, signal);
  },
};

export type { AppDeployment };
