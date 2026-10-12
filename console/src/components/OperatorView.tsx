'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Activity,
  Boxes,
  Cpu,
  Database,
  HardDrive,
  Info,
  Loader2,
  Lock,
  RefreshCw,
  Server,
  ShieldCheck,
  TriangleAlert,
} from 'lucide-react';
import { api, ApiError } from '@/lib/api';
import type { Health, Node, Whoami } from '@/lib/types';
import { cn, formatRelative, toneClasses } from '@/lib/utils';
import type { OperatorTab } from './Sidebar';

interface OperatorViewProps {
  whoami: Whoami;
  activeTab: OperatorTab;
  refreshKey: number;
  health: Health | null;
}

/**
 * The system/cluster dashboard. Operators see infrastructure only: node
 * membership, capacity, and load. Tenant data and secrets are deliberately out
 * of reach (see the D-044 notice).
 */
export function OperatorView({ whoami, activeTab, refreshKey, health }: OperatorViewProps) {
  const [nodes, setNodes] = useState<Node[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async (signal?: AbortSignal) => {
    setLoading(true);
    try {
      const { nodes: list } = await api.getNodes(signal);
      setNodes(list);
      setError(null);
    } catch (cause) {
      if (cause instanceof DOMException && cause.name === 'AbortError') return;
      setError(cause instanceof ApiError ? cause.message : 'Failed to load cluster nodes.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    void load(controller.signal);
    return () => controller.abort();
  }, [load, refreshKey]);

  const totals = useMemo(() => {
    const capacity = nodes.reduce((sum, node) => sum + (node.capacity || 0), 0);
    const running = nodes.reduce((sum, node) => sum + (node.running || 0), 0);
    return { capacity, running, free: Math.max(0, capacity - running) };
  }, [nodes]);

  return (
    <div className="space-y-4">
      {error ? (
        <div className="flex items-start gap-3 rounded-lg border border-danger/40 bg-danger/10 p-4 text-sm text-danger">
          <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
          <span className="flex-1">{error}</span>
          <button type="button" onClick={() => void load()} className="btn btn-ghost">
            Retry
          </button>
        </div>
      ) : null}

      {activeTab === 'overview' ? (
        <>
          <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
            <StatCard icon={Boxes} label="Nodes" value={nodes.length} hint="live in cluster" />
            <StatCard icon={Cpu} label="Capacity" value={totals.capacity} hint="execution slots" />
            <StatCard icon={Activity} label="Running" value={totals.running} hint="slots in use" />
            <StatCard icon={HardDrive} label="Free" value={totals.free} hint="slots available" />
          </div>

          <HealthPanel health={health} nodes={nodes} loading={loading} />

          <section className="panel">
            <div className="panel-header">
              <div>
                <h2 className="text-sm font-semibold tracking-tight">Cluster Topology</h2>
                <p className="text-xs text-subtle">Live node membership and load.</p>
              </div>
              <button
                type="button"
                onClick={() => void load()}
                className="btn btn-ghost"
                aria-label="Reload nodes"
              >
                <RefreshCw className={cn('h-4 w-4', loading && 'animate-spin')} aria-hidden />
              </button>
            </div>
            {loading ? (
              <LoadingRow label="Loading nodes…" />
            ) : nodes.length === 0 ? (
              <EmptyRow label="No live nodes reported." />
            ) : (
              <div className="grid gap-3 p-4 sm:grid-cols-2 xl:grid-cols-3">
                {nodes.map((node) => (
                  <NodeCard key={node.id} node={node} />
                ))}
              </div>
            )}
          </section>

          <D044Notice />
        </>
      ) : null}

      {activeTab === 'nodes' ? (
        <section className="panel">
          <div className="panel-header">
            <div>
              <h2 className="text-sm font-semibold tracking-tight">Cluster Nodes</h2>
              <p className="text-xs text-subtle">Every node with capacity, load, and heartbeat.</p>
            </div>
            <button
              type="button"
              onClick={() => void load()}
              className="btn btn-ghost"
              aria-label="Reload nodes"
            >
              <RefreshCw className={cn('h-4 w-4', loading && 'animate-spin')} aria-hidden />
            </button>
          </div>
          {loading ? (
            <LoadingRow label="Loading nodes…" />
          ) : nodes.length === 0 ? (
            <EmptyRow label="No live nodes reported." />
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm">
                <thead className="text-xs uppercase tracking-wider text-subtle">
                  <tr className="border-b border-border/70">
                    <th className="px-5 py-2.5 font-medium">Node</th>
                    <th className="px-5 py-2.5 font-medium">Roles</th>
                    <th className="px-5 py-2.5 font-medium">Capacity</th>
                    <th className="px-5 py-2.5 font-medium">Running</th>
                    <th className="px-5 py-2.5 font-medium">Last seen</th>
                    <th className="px-5 py-2.5 font-medium">Endpoint</th>
                  </tr>
                </thead>
                <tbody>
                  {nodes.map((node) => (
                    <tr
                      key={node.id}
                      className="border-b border-border/40 last:border-0 hover:bg-elevated/40"
                    >
                      <td className="px-5 py-3">
                        <div className="font-medium text-foreground">{node.id}</div>
                        <div className="font-mono text-[11px] text-subtle">
                          {node.instanceId ?? '—'}
                        </div>
                      </td>
                      <td className="px-5 py-3">
                        <RoleChips node={node} />
                      </td>
                      <td className="px-5 py-3 tabular-nums text-muted">{node.capacity}</td>
                      <td className="px-5 py-3 tabular-nums text-muted">{node.running}</td>
                      <td className="px-5 py-3 text-xs text-muted">
                        {formatRelative(node.lastSeen)}
                      </td>
                      <td className="px-5 py-3 font-mono text-xs text-muted">
                        {node.endpoint ?? '—'}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>
      ) : null}

      {activeTab === 'storage' ? (
        <section className="panel p-5">
          <h2 className="flex items-center gap-2 text-sm font-semibold tracking-tight">
            <Database className="h-4 w-4 text-accent" aria-hidden />
            Storage &amp; Memory
          </h2>
          <p className="mt-2 text-sm text-muted">
            Cluster execution capacity across {nodes.length} node{nodes.length === 1 ? '' : 's'}.
          </p>
          <div className="mt-4 grid gap-4 sm:grid-cols-3">
            <MiniStat label="Total capacity" value={totals.capacity} />
            <MiniStat label="In use" value={totals.running} />
            <MiniStat label="Available" value={totals.free} />
          </div>
          <div className="mt-5">
            <div className="flex items-center justify-between text-xs text-subtle">
              <span>Utilization</span>
              <span className="tabular-nums">
                {totals.capacity > 0
                  ? `${Math.round((totals.running / totals.capacity) * 100)}%`
                  : '0%'}
              </span>
            </div>
            <div className="mt-2 h-2 overflow-hidden rounded-full bg-elevated">
              <div
                className="h-full rounded-full bg-accent transition-all"
                style={{
                  width: `${
                    totals.capacity > 0
                      ? Math.min(100, Math.round((totals.running / totals.capacity) * 100))
                      : 0
                  }%`,
                }}
              />
            </div>
          </div>
          <div className="mt-6 flex items-start gap-3 rounded-lg border border-border/70 bg-elevated/40 p-4 text-sm text-muted">
            <Info className="mt-0.5 h-4 w-4 shrink-0 text-subtle" aria-hidden />
            <span>
              Durable state (Apache Ignite WAL, caches, and the VFS) is confined to{' '}
              <code className="rounded bg-background/60 px-1.5 py-0.5 font-mono text-xs text-foreground">
                automately-cluster-ignite
              </code>
              . The API exposes execution capacity and load here; per-cache memory statistics are
              not part of the public contract.
            </span>
          </div>
        </section>
      ) : null}

      {activeTab === 'settings' ? (
        <div className="space-y-4">
          <section className="panel p-5">
            <h2 className="flex items-center gap-2 text-sm font-semibold tracking-tight">
              <Server className="h-4 w-4 text-accent" aria-hidden />
              Operator Identity
            </h2>
            <dl className="mt-4 grid gap-4 sm:grid-cols-2">
              <Field label="Scope" value="Operator (cluster wide)" />
              <Field label="Admin token" value={whoami.admin ? 'yes' : 'no'} />
              <Field label="User" value={whoami.user ?? '—'} />
              <Field label="Serving node" value={health?.node ?? '—'} />
              <Field
                label="Scopes"
                value={whoami.scopes && whoami.scopes.length > 0 ? whoami.scopes.join(', ') : 'all'}
              />
            </dl>
          </section>
          <D044Notice />
        </div>
      ) : null}
    </div>
  );
}

function NodeCard({ node }: { node: Node }) {
  const utilization = node.capacity > 0 ? Math.round((node.running / node.capacity) * 100) : 0;
  return (
    <div className="rounded-xl border border-border/70 bg-elevated/30 p-4">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="truncate font-medium text-foreground" title={node.id}>
            {node.id}
          </div>
          <div className="truncate font-mono text-[11px] text-subtle" title={node.instanceId}>
            {node.instanceId ?? '—'}
          </div>
        </div>
        <Server className="h-4 w-4 shrink-0 text-subtle" aria-hidden />
      </div>
      <div className="mt-3">
        <RoleChips node={node} />
      </div>
      <div className="mt-3 flex items-center justify-between text-xs text-subtle">
        <span>
          <span className="tabular-nums text-foreground">{node.running}</span> / {node.capacity}{' '}
          slots
        </span>
        <span>seen {formatRelative(node.lastSeen)}</span>
      </div>
      <div className="mt-2 h-1.5 overflow-hidden rounded-full bg-background/70">
        <div
          className={cn(
            'h-full rounded-full',
            utilization > 85 ? 'bg-danger' : utilization > 60 ? 'bg-warning' : 'bg-accent',
          )}
          style={{ width: `${Math.min(100, utilization)}%` }}
        />
      </div>
    </div>
  );
}

function RoleChips({ node }: { node: Node }) {
  // A node advertises roles only in newer contracts. When absent, infer from
  // capacity: the server gives execution slots to workers and zero to every
  // other persona (see Automately.java), so capacity > 0 means "worker" and a
  // zero-capacity node serves the api/ingress edge.
  const roles = node.roles?.length
    ? node.roles
    : node.capacity > 0
      ? ['worker']
      : ['api', 'ingress'];
  return (
    <div className="flex flex-wrap gap-1.5">
      {roles.map((role) => (
        <span
          key={role}
          className="rounded-md border border-border/70 bg-background/50 px-2 py-0.5 font-mono text-[11px] text-muted"
        >
          {role}
        </span>
      ))}
    </div>
  );
}

function HealthPanel({
  health,
  nodes,
  loading,
}: {
  health: Health | null;
  nodes: Node[];
  loading: boolean;
}) {
  const healthy = Boolean(health && health.status === 'UP');
  return (
    <section className="panel p-5">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div className="flex items-center gap-3">
          <span
            className={cn(
              'badge',
              healthy ? toneClasses('positive') : toneClasses('danger'),
            )}
          >
            <ShieldCheck className="h-3.5 w-3.5" aria-hidden />
            {healthy ? 'Cluster healthy' : 'Health unknown'}
          </span>
          <span className="text-sm text-muted">
            {loading
              ? 'Checking…'
              : `${nodes.length} node${nodes.length === 1 ? '' : 's'} reporting`}
          </span>
        </div>
        <span className="text-xs text-subtle">
          Liveness from <code className="font-mono">GET /health</code>
          {health?.node ? ` · ${health.node}` : ''}
        </span>
      </div>
    </section>
  );
}

function D044Notice() {
  return (
    <section className="panel border-accent/20 p-5">
      <div className="flex items-start gap-3">
        <Lock className="mt-0.5 h-5 w-5 shrink-0 text-accent" aria-hidden />
        <div>
          <h3 className="text-sm font-semibold tracking-tight">Tenant data stays isolated</h3>
          <p className="mt-1 text-sm text-muted">
            Per decision <span className="font-mono text-foreground">D-044</span>, operator access
            is limited to cluster infrastructure. Tenant private data — job inputs and outputs,
            script logs, secrets, and VFS contents — is not readable by operators through this
            console or the public API.
          </p>
        </div>
      </div>
    </section>
  );
}

function StatCard({
  icon: Icon,
  label,
  value,
  hint,
}: {
  icon: React.ComponentType<{ className?: string; 'aria-hidden'?: boolean }>;
  label: string;
  value: number;
  hint?: string;
}) {
  return (
    <div className="panel p-5">
      <div className="flex items-center justify-between">
        <span className="stat-label">{label}</span>
        <Icon className="h-4 w-4 text-subtle" aria-hidden />
      </div>
      <div className="mt-3 stat-value">{value}</div>
      {hint ? <div className="mt-1 text-xs text-subtle">{hint}</div> : null}
    </div>
  );
}

function MiniStat({ label, value }: { label: string; value: number }) {
  return (
    <div className="rounded-lg border border-border/70 bg-elevated/30 p-4">
      <div className="stat-label">{label}</div>
      <div className="mt-2 text-xl font-semibold tabular-nums">{value}</div>
    </div>
  );
}

function Field({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="stat-label">{label}</dt>
      <dd className="mt-1 break-words font-mono text-sm text-foreground">{value}</dd>
    </div>
  );
}

function LoadingRow({ label }: { label: string }) {
  return (
    <div className="flex items-center justify-center gap-2 p-12 text-sm text-muted">
      <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
      {label}
    </div>
  );
}

function EmptyRow({ label }: { label: string }) {
  return <div className="p-12 text-center text-sm text-subtle">{label}</div>;
}
