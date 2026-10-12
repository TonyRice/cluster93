'use client';

import { useCallback, useEffect, useState } from 'react';
import { Activity, Loader2, Play, RefreshCw, RotateCw, Square, TriangleAlert } from 'lucide-react';
import { api, ApiError } from '@/lib/api';
import type { Service } from '@/lib/types';
import { cn, formatRelative, serviceStatusTone, toneClasses } from '@/lib/utils';

interface ServicesTabProps {
  refreshKey: number;
}

/** Lists supervised services with status, hosts, revision, and lifecycle actions. */
export function ServicesTab({ refreshKey }: ServicesTabProps) {
  const [services, setServices] = useState<Service[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<Record<string, boolean>>({});

  const load = useCallback(async (signal?: AbortSignal) => {
    setLoading(true);
    try {
      const { services: list } = await api.getServices(signal);
      setServices(list);
      setError(null);
    } catch (cause) {
      if (cause instanceof DOMException && cause.name === 'AbortError') return;
      setError(cause instanceof ApiError ? cause.message : 'Failed to load services.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    void load(controller.signal);
    return () => controller.abort();
  }, [load, refreshKey]);

  async function act(name: string, action: 'restart' | 'stop') {
    setBusy((prev) => ({ ...prev, [name]: true }));
    try {
      if (action === 'restart') await api.restartService(name);
      else await api.stopService(name);
      await load();
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : `Failed to ${action} service.`);
    } finally {
      setBusy((prev) => ({ ...prev, [name]: false }));
    }
  }

  return (
    <section className="panel">
      <div className="panel-header">
        <div>
          <h2 className="text-sm font-semibold tracking-tight">Services</h2>
          <p className="text-xs text-subtle">Long-running workloads kept alive by the supervisor.</p>
        </div>
        <button
          type="button"
          onClick={() => void load()}
          className="btn btn-ghost"
          aria-label="Reload services"
        >
          <RefreshCw className={cn('h-4 w-4', loading && 'animate-spin')} aria-hidden />
        </button>
      </div>

      {loading ? (
        <div className="flex items-center justify-center gap-2 p-12 text-sm text-muted">
          <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
          Loading services…
        </div>
      ) : error ? (
        <div className="p-6">
          <div className="flex items-start gap-3 rounded-lg border border-danger/40 bg-danger/10 p-4 text-sm text-danger">
            <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
            <span className="flex-1">{error}</span>
            <button type="button" onClick={() => void load()} className="btn btn-ghost">
              Retry
            </button>
          </div>
        </div>
      ) : services.length === 0 ? (
        <div className="flex flex-col items-center justify-center gap-2 p-12 text-center text-sm text-subtle">
          <Activity className="h-6 w-6" aria-hidden />
          No services defined.
        </div>
      ) : (
        <ul className="divide-y divide-border/40">
          {services.map((service) => {
            const running = service.status.toUpperCase() === 'RUNNING';
            return (
              <li key={service.name} className="flex flex-wrap items-center gap-4 px-5 py-4">
                <div className="min-w-48 flex-1">
                  <div className="flex items-center gap-2">
                    <span className="font-medium text-foreground">{service.name}</span>
                    <span className={cn('badge', toneClasses(serviceStatusTone(service.status)))}>
                      {service.status}
                    </span>
                    {!service.enabled ? (
                      <span className="badge border-border/80 bg-elevated text-subtle">disabled</span>
                    ) : null}
                  </div>
                  <div className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-subtle">
                    <span>revision {service.revision}</span>
                    <span>·</span>
                    <span>{service.runs} run{service.runs === 1 ? '' : 's'}</span>
                    {service.consecutiveFailures ? (
                      <>
                        <span>·</span>
                        <span className="text-danger">
                          {service.consecutiveFailures} consecutive failure
                          {service.consecutiveFailures === 1 ? '' : 's'}
                        </span>
                      </>
                    ) : null}
                    {service.updatedAt ? (
                      <>
                        <span>·</span>
                        <span>updated {formatRelative(service.updatedAt)}</span>
                      </>
                    ) : null}
                  </div>
                </div>

                <div className="min-w-48 flex-1">
                  <div className="stat-label">Active hosts</div>
                  <div className="mt-1 flex flex-wrap gap-1.5">
                    {service.hosts.length === 0 ? (
                      <span className="text-xs text-subtle">none</span>
                    ) : (
                      service.hosts.map((host) => (
                        <span
                          key={host}
                          className="rounded-md border border-border/70 bg-elevated/60 px-2 py-0.5 font-mono text-[11px] text-muted"
                        >
                          {host}
                        </span>
                      ))
                    )}
                  </div>
                </div>

                <div className="flex items-center gap-2">
                  <button
                    type="button"
                    disabled={busy[service.name]}
                    onClick={() => void act(service.name, 'restart')}
                    className="btn btn-ghost"
                    title="Restart service"
                  >
                    {busy[service.name] ? (
                      <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
                    ) : (
                      <RotateCw className="h-4 w-4" aria-hidden />
                    )}
                    Restart
                  </button>
                  <button
                    type="button"
                    disabled={busy[service.name] || !running}
                    onClick={() => void act(service.name, 'stop')}
                    className="btn btn-danger"
                    title={running ? 'Stop service' : 'Service is not running'}
                  >
                    <Square className="h-4 w-4" aria-hidden />
                    Stop
                  </button>
                </div>
              </li>
            );
          })}
        </ul>
      )}
      {services.length > 0 ? (
        <div className="flex items-center gap-2 border-t border-border/70 px-5 py-3 text-xs text-subtle">
          <Play className="h-3.5 w-3.5" aria-hidden />
          Services restart automatically according to their policy; use Restart to force a run now.
        </div>
      ) : null}
    </section>
  );
}
