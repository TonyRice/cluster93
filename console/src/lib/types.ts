/**
 * TypeScript models mirroring the Automately OpenAPI contract
 * (`automately-server/src/main/resources/openapi.yaml`).
 *
 * Fields documented as optional in the contract are marked optional here so the
 * console keeps compiling if a newer, narrower server response omits them.
 */

/** `GET /v1/whoami` */
export interface Whoami {
  admin: boolean;
  tenant?: string;
  token?: string | null;
  /** The user's name, for a personal token (D-070). */
  user?: string | null;
  userId?: string | null;
  owner?: boolean;
  scopes?: string[];
}

/** `GET /health` */
export interface Health {
  status: 'UP';
  node: string;
}

/**
 * `GET /v1/nodes`
 *
 * The contract exposes capacity/load only. `roles` is not part of the current
 * response; it is accepted here so the topology cards can render role chips when
 * a future node advertises them, without pretending to know what they are.
 */
export interface Node {
  id: string;
  instanceId?: string;
  capacity: number;
  running: number;
  lastSeen: number;
  endpoint?: string | null;
  roles?: string[];
}

export interface NodeList {
  nodes: Node[];
}

export type JobStatus =
  | 'QUEUED'
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'TIMED_OUT'
  | 'ORPHANED'
  | 'CANCELLED';

export type JobOutcome =
  | 'SUCCEEDED'
  | 'FAILED'
  | 'TIMED_OUT'
  | 'ORPHANED'
  | 'CANCELLED'
  | 'running';

export interface JobAttempt {
  attempt: number;
  node?: string | null;
  startedAt?: number | null;
  endedAt?: number | null;
  outcome?: JobOutcome | string;
}

/** `GET /v1/jobs`, `GET /v1/jobs/{id}` */
export interface Job {
  id: string;
  tenant?: string;
  input?: Record<string, unknown> | null;
  timeoutMs?: number;
  onOrphan?: 'fail' | 'retry' | 'resume';
  maxAttempts?: number;
  status: JobStatus;
  attempt: number;
  node?: string | null;
  cancelRequested?: boolean | null;
  service?: Record<string, unknown> | null;
  output?: unknown;
  error?: string | null;
  logs?: string[];
  attempts?: JobAttempt[];
  createdAt: number;
  queuedAt?: number | null;
  startedAt?: number | null;
  finishedAt?: number | null;
  parent?: string | null;
  depth?: number;
}

export interface JobList {
  jobs: Job[];
}

export interface ServiceRoute {
  host?: string;
  path?: string;
  port?: number;
  exposed?: false;
}

export interface ServicePort {
  port: number;
  path?: string;
  expose?: boolean;
}

/** `GET /v1/services` */
export interface Service {
  name: string;
  script?: string;
  config?: Record<string, unknown> | null;
  restart?: 'always' | 'on-failure';
  enabled: boolean;
  completed?: boolean;
  status: string;
  revision: number;
  currentJobId?: string | null;
  currentRevision?: number | null;
  runs: number;
  consecutiveFailures?: number;
  nextStartAt?: number | null;
  lastExit?: Record<string, unknown> | null;
  hosts: string[];
  routes?: ServiceRoute[];
  ports?: ServicePort[] | null;
  createdAt?: number;
  updatedAt?: number;
  secrets?: boolean;
}

export interface ServiceList {
  services: Service[];
}

/** `GET /v1/apps` */
export interface App {
  tenant: string;
  name: string;
  activeDeploymentId?: string | null;
  updatedAt: number;
  /** The deployments promoted through, oldest first; the active one is last. */
  deploymentHistory: string[];
}

export interface AppList {
  apps: App[];
}

/** `GET /v1/apps/{app}/deployments` */
export interface AppDeployment {
  tenant: string;
  app: string;
  deploymentId: string;
  createdAt: number;
  commitHash?: string | null;
  manifest: Record<string, unknown>;
  vfsPrefix: string;
  serviceRevision?: number | null;
}

export interface AppDeploymentList {
  deployments: AppDeployment[];
}

/** `GET /v1/files` */
export interface FileEntry {
  path: string;
  size: number;
  contentType: string;
  etag: string;
  createdAt: number;
}

export interface FileList {
  files: FileEntry[];
  cursor?: string | null;
}

/** The `message`/`code` shape the API returns on failure. */
export interface ApiErrorBody {
  error: {
    code: string | number;
    message: string;
  };
}
