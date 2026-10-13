'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  ExternalLink,
  History,
  Loader2,
  Rocket,
  RotateCcw,
  Settings2,
  TriangleAlert,
} from 'lucide-react';
import { api, ApiError } from '@/lib/api';
import type { App, AppDeployment } from '@/lib/types';
import { cn, formatDateTime, formatRelative } from '@/lib/utils';

interface AppsTabProps {
  refreshKey: number;
}

const INGRESS_HOST_STORAGE_KEY = 'c93_ingress_host';

/**
 * Derives the default ingress host (including port if present) from the current origin
 * so preview and canonical URLs can be routed properly.
 * If accessed at `console.<ingress>` or `<deployment>--console--system.<ingress>`,
 * the leading labels are stripped down to the ingress host.
 */
export function defaultIngressHost(): string {
  if (typeof window === 'undefined') return '';
  const { host, hostname, port } = window.location;
  const labels = hostname.split('.');
  let baseDomain = hostname;
  if (labels.length > 1 && (labels[0].startsWith('console') || labels[0].includes('--console--'))) {
    baseDomain = labels.slice(1).join('.');
  }
  return port ? `${baseDomain}:${port}` : baseDomain;
}

export function buildAppUrl(subdomain: string, ingressHost: string): string {
  const host = ingressHost.trim();
  const protocol = typeof window !== 'undefined' ? window.location.protocol : 'http:';
  if (!host) return '';
  return `${protocol}//${subdomain}.${host}/`;
}

/** Lists deployed apps with their live deployment, preview URLs, and actions. */
export function AppsTab({ refreshKey }: AppsTabProps) {
  const [apps, setApps] = useState<App[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [deployments, setDeployments] = useState<AppDeployment[]>([]);
  const [loading, setLoading] = useState(true);
  const [detailLoading, setDetailLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [ingressHost, setIngressHost] = useState<string>(() => {
    if (typeof window === 'undefined') return '';
    return localStorage.getItem(INGRESS_HOST_STORAGE_KEY) || defaultIngressHost();
  });
  const [editingHost, setEditingHost] = useState(false);
  const [hostDraft, setHostDraft] = useState(ingressHost);

  const handleSaveHost = useCallback((e?: React.FormEvent) => {
    if (e) e.preventDefault();
    const trimmed = hostDraft.trim();
    if (!trimmed) {
      const fallback = defaultIngressHost();
      setIngressHost(fallback);
      setHostDraft(fallback);
      localStorage.removeItem(INGRESS_HOST_STORAGE_KEY);
    } else {
      setIngressHost(trimmed);
      localStorage.setItem(INGRESS_HOST_STORAGE_KEY, trimmed);
    }
    setEditingHost(false);
  }, [hostDraft]);

  const handleResetHost = useCallback(() => {
    const fallback = defaultIngressHost();
    setIngressHost(fallback);
    setHostDraft(fallback);
    localStorage.removeItem(INGRESS_HOST_STORAGE_KEY);
    setEditingHost(false);
  }, []);

  const loadApps = useCallback(async (signal?: AbortSignal) => {
    setLoading(true);
    try {
      const { apps: list } = await api.getApps(signal);
      setApps(list);
      setError(null);
      setSelected((current) =>
        current && list.some((app) => app.name === current) ? current : list[0]?.name ?? null,
      );
    } catch (cause) {
      if (cause instanceof DOMException && cause.name === 'AbortError') return;
      setError(cause instanceof ApiError ? cause.message : 'Failed to load apps.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    void loadApps(controller.signal);
    return () => controller.abort();
  }, [loadApps, refreshKey]);

  const selectedApp = useMemo(
    () => apps.find((app) => app.name === selected) ?? null,
    [apps, selected],
  );

  useEffect(() => {
    if (!selectedApp) {
      setDeployments([]);
      return;
    }
    const controller = new AbortController();
    setDetailLoading(true);
    api
      .getDeployments(selectedApp.name, controller.signal)
      .then(({ deployments: list }) => {
        setDeployments([...list].sort((a, b) => b.createdAt - a.createdAt));
      })
      .catch((cause) => {
        if (cause instanceof DOMException && cause.name === 'AbortError') return;
        setError(cause instanceof ApiError ? cause.message : 'Failed to load deployments.');
      })
      .finally(() => setDetailLoading(false));
    return () => controller.abort();
  }, [selectedApp]);

  async function promote(app: App, deploymentId: string) {
    setBusy(deploymentId);
    try {
      const updated = await api.promoteApp(app.name, deploymentId);
      setApps((prev) => prev.map((item) => (item.name === updated.name ? updated : item)));
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : 'Failed to promote deployment.');
    } finally {
      setBusy(null);
    }
  }

  async function rollback(app: App) {
    setBusy('__rollback__');
    try {
      const updated = await api.rollbackApp(app.name);
      setApps((prev) => prev.map((item) => (item.name === updated.name ? updated : item)));
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : 'Failed to roll back app.');
    } finally {
      setBusy(null);
    }
  }

  if (loading) {
    return (
      <section className="panel">
        <div className="flex items-center justify-center gap-2 p-12 text-sm text-muted">
          <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
          Loading apps…
        </div>
      </section>
    );
  }

  if (error) {
    return (
      <section className="panel">
        <div className="p-6">
          <div className="flex items-start gap-3 rounded-lg border border-danger/40 bg-danger/10 p-4 text-sm text-danger">
            <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
            <span className="flex-1">{error}</span>
            <button type="button" onClick={() => void loadApps()} className="btn btn-ghost">
              Retry
            </button>
          </div>
        </div>
      </section>
    );
  }

  if (apps.length === 0) {
    return (
      <section className="panel">
        <div className="flex flex-col items-center justify-center gap-2 p-12 text-center text-sm text-subtle">
          <Rocket className="h-6 w-6" aria-hidden />
          No apps deployed yet. Use{' '}
          <code className="rounded bg-elevated px-1.5 py-0.5 font-mono text-xs text-foreground">
            c93 apps deploy
          </code>{' '}
          to publish one.
        </div>
      </section>
    );
  }

  return (
    <div className="grid gap-4 lg:grid-cols-[18rem_1fr]">
      <section className="panel h-fit">
        <div className="panel-header">
          <h2 className="text-sm font-semibold tracking-tight">Apps</h2>
          <span className="text-xs text-subtle">{apps.length}</span>
        </div>
        <div className="border-b border-border/60 px-3 py-2 text-xs">
          <div className="flex items-center justify-between text-subtle">
            <span className="flex items-center gap-1 font-medium">
              <Settings2 className="h-3.5 w-3.5" aria-hidden />
              Ingress Host
            </span>
            <button
              type="button"
              onClick={() => {
                setHostDraft(ingressHost);
                setEditingHost(!editingHost);
              }}
              className="text-[11px] text-accent hover:underline"
            >
              {editingHost ? 'Cancel' : 'Configure'}
            </button>
          </div>
          {editingHost ? (
            <form onSubmit={handleSaveHost} className="mt-2 space-y-1.5">
              <input
                type="text"
                value={hostDraft}
                onChange={(e) => setHostDraft(e.target.value)}
                placeholder="e.g. localhost:8000"
                className="input h-7 w-full font-mono text-xs"
                autoFocus
              />
              <div className="flex items-center justify-end gap-1.5 pt-1">
                <button
                  type="button"
                  onClick={handleResetHost}
                  className="btn btn-ghost h-6 px-2 text-[11px]"
                >
                  Reset
                </button>
                <button type="submit" className="btn btn-primary h-6 px-2 text-[11px]">
                  Save
                </button>
              </div>
            </form>
          ) : (
            <div className="mt-1 font-mono text-[11px] text-muted truncate" title={ingressHost}>
              {ingressHost || '(default)'}
            </div>
          )}
        </div>
        <ul className="p-2">
          {apps.map((app) => (
            <li key={app.name}>
              <button
                type="button"
                onClick={() => setSelected(app.name)}
                aria-current={app.name === selected ? 'true' : undefined}
                className={cn(
                  'flex w-full flex-col items-start rounded-lg px-3 py-2 text-left transition-colors',
                  app.name === selected
                    ? 'bg-elevated text-foreground ring-1 ring-border/80'
                    : 'text-muted hover:bg-elevated/60 hover:text-foreground',
                )}
              >
                <span className="text-sm font-medium">{app.name}</span>
                <span className="font-mono text-[11px] text-subtle">
                  {app.activeDeploymentId ? `live: ${app.activeDeploymentId.slice(0, 12)}` : 'not live'}
                </span>
              </button>
            </li>
          ))}
        </ul>
      </section>

      {selectedApp ? (
        <section className="panel">
          <div className="panel-header">
            <div>
              <h2 className="flex items-center gap-2 text-sm font-semibold tracking-tight">
                {selectedApp.name}
                {selectedApp.activeDeploymentId ? (
                  <span className="badge border-positive/40 bg-positive/10 text-positive">
                    live
                  </span>
                ) : (
                  <span className="badge border-border/80 bg-elevated text-subtle">
                    not deployed
                  </span>
                )}
              </h2>
              <p className="text-xs text-subtle">
                tenant {selectedApp.tenant} · updated {formatRelative(selectedApp.updatedAt)}
              </p>
            </div>
            <div className="flex items-center gap-2">
              {selectedApp.activeDeploymentId ? (
                <a
                  href={buildAppUrl(`${selectedApp.name}--${selectedApp.tenant}`, ingressHost)}
                  target="_blank"
                  rel="noreferrer"
                  className="btn btn-ghost"
                  title="Open canonical URL"
                >
                  <ExternalLink className="h-4 w-4" aria-hidden />
                  Open
                </a>
              ) : null}
              <button
                type="button"
                onClick={() => void rollback(selectedApp)}
                disabled={busy !== null || (selectedApp.deploymentHistory?.length ?? 0) < 2}
                className="btn btn-ghost"
                title="Roll back to the previously promoted deployment"
              >
                {busy === '__rollback__' ? (
                  <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
                ) : (
                  <RotateCcw className="h-4 w-4" aria-hidden />
                )}
                Roll back
              </button>
            </div>
          </div>

          <div className="flex items-center gap-2 border-b border-border/70 px-5 py-3 text-xs text-subtle">
            <History className="h-3.5 w-3.5" aria-hidden />
            {selectedApp.deploymentHistory?.length ?? 0} promoted deployment
            {(selectedApp.deploymentHistory?.length ?? 0) === 1 ? '' : 's'} ·{' '}
            {deployments.length} total
          </div>

          {detailLoading ? (
            <div className="flex items-center justify-center gap-2 p-10 text-sm text-muted">
              <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
              Loading deployments…
            </div>
          ) : deployments.length === 0 ? (
            <div className="p-10 text-center text-sm text-subtle">No deployments registered.</div>
          ) : (
            <ul className="divide-y divide-border/40">
              {deployments.map((deployment) => {
                const isActive = deployment.deploymentId === selectedApp.activeDeploymentId;
                const preview = buildAppUrl(
                  `${deployment.deploymentId}--${selectedApp.name}--${selectedApp.tenant}`,
                  ingressHost,
                );
                return (
                  <li
                    key={deployment.deploymentId}
                    className="flex flex-wrap items-center gap-4 px-5 py-4"
                  >
                    <div className="min-w-56 flex-1">
                      <div className="flex items-center gap-2">
                        <span className="font-mono text-xs text-foreground">
                          {deployment.deploymentId}
                        </span>
                        {isActive ? (
                          <span className="badge border-accent/40 bg-accent/10 text-accent">
                            active
                          </span>
                        ) : null}
                      </div>
                      <div className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-subtle">
                        <span title={formatDateTime(deployment.createdAt)}>
                          {formatRelative(deployment.createdAt)}
                        </span>
                        {deployment.commitHash ? (
                          <>
                            <span>·</span>
                            <span className="font-mono">{deployment.commitHash.slice(0, 10)}</span>
                          </>
                        ) : null}
                        <span>·</span>
                        <span className="font-mono">{deployment.vfsPrefix}</span>
                      </div>
                    </div>

                    <div className="min-w-56 flex-1">
                      <a
                        href={preview}
                        target="_blank"
                        rel="noreferrer"
                        className="inline-flex items-center gap-1.5 font-mono text-xs text-accent hover:underline"
                        title="Open preview deployment"
                      >
                        <ExternalLink className="h-3.5 w-3.5" aria-hidden />
                        preview
                      </a>
                    </div>

                    <button
                      type="button"
                      disabled={isActive || busy !== null}
                      onClick={() => void promote(selectedApp, deployment.deploymentId)}
                      className="btn btn-primary"
                      title={isActive ? 'Already active' : 'Promote this deployment'}
                    >
                      {busy === deployment.deploymentId ? (
                        <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
                      ) : (
                        <Rocket className="h-4 w-4" aria-hidden />
                      )}
                      Promote
                    </button>
                  </li>
                );
              })}
            </ul>
          )}
        </section>
      ) : null}
    </div>
  );
}
