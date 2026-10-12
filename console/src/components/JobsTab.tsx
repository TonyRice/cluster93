'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Ban, Loader2, RefreshCw, ScrollText, TriangleAlert } from 'lucide-react';
import { api, ApiError } from '@/lib/api';
import type { Job } from '@/lib/types';
import {
  cn,
  formatDateTime,
  formatDuration,
  jobDuration,
  jobStatusTone,
  toneClasses,
} from '@/lib/utils';

interface JobsTabProps {
  /** Bumped by the shell to force a refetch. */
  refreshKey: number;
}

const FILTERS = ['ALL', 'QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED'] as const;
type Filter = (typeof FILTERS)[number];

/** Lists the tenant's jobs with status, duration, and a cancel action. */
export function JobsTab({ refreshKey }: JobsTabProps) {
  const [jobs, setJobs] = useState<Job[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [filter, setFilter] = useState<Filter>('ALL');
  const [busy, setBusy] = useState<Record<string, boolean>>({});

  const load = useCallback(async (signal?: AbortSignal) => {
    setLoading(true);
    try {
      const { jobs: list } = await api.getJobs(signal);
      setJobs(list);
      setError(null);
    } catch (cause) {
      if (cause instanceof DOMException && cause.name === 'AbortError') return;
      setError(cause instanceof ApiError ? cause.message : 'Failed to load jobs.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    void load(controller.signal);
    return () => controller.abort();
  }, [load, refreshKey]);

  const visible = useMemo(
    () => (filter === 'ALL' ? jobs : jobs.filter((job) => job.status === filter)),
    [jobs, filter],
  );

  async function cancel(job: Job) {
    setBusy((prev) => ({ ...prev, [job.id]: true }));
    try {
      await api.cancelJob(job.id);
      await load();
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : 'Failed to cancel job.');
    } finally {
      setBusy((prev) => ({ ...prev, [job.id]: false }));
    }
  }

  return (
    <section className="panel">
      <div className="panel-header">
        <div>
          <h2 className="text-sm font-semibold tracking-tight">Jobs</h2>
          <p className="text-xs text-subtle">Durable executions, newest first.</p>
        </div>
        <div className="flex items-center gap-2">
          <div className="flex rounded-lg border border-border/80 p-0.5">
            {FILTERS.map((value) => (
              <button
                key={value}
                type="button"
                onClick={() => setFilter(value)}
                className={cn(
                  'rounded-md px-2.5 py-1 text-xs font-medium transition-colors',
                  filter === value
                    ? 'bg-elevated text-foreground'
                    : 'text-subtle hover:text-foreground',
                )}
              >
                {value === 'ALL' ? 'All' : value[0] + value.slice(1).toLowerCase()}
              </button>
            ))}
          </div>
          <button
            type="button"
            onClick={() => void load()}
            className="btn btn-ghost"
            aria-label="Reload jobs"
          >
            <RefreshCw className={cn('h-4 w-4', loading && 'animate-spin')} aria-hidden />
          </button>
        </div>
      </div>

      {loading ? (
        <div className="flex items-center justify-center gap-2 p-12 text-sm text-muted">
          <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
          Loading jobs…
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
      ) : visible.length === 0 ? (
        <div className="flex flex-col items-center justify-center gap-2 p-12 text-center text-sm text-subtle">
          <ScrollText className="h-6 w-6" aria-hidden />
          {jobs.length === 0 ? 'No jobs yet.' : 'No jobs match this filter.'}
        </div>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-left text-sm">
            <thead className="text-xs uppercase tracking-wider text-subtle">
              <tr className="border-b border-border/70">
                <th className="px-5 py-2.5 font-medium">ID</th>
                <th className="px-5 py-2.5 font-medium">Status</th>
                <th className="px-5 py-2.5 font-medium">Duration</th>
                <th className="px-5 py-2.5 font-medium">Node</th>
                <th className="px-5 py-2.5 font-medium">Created</th>
                <th className="px-5 py-2.5 text-right font-medium">Actions</th>
              </tr>
            </thead>
            <tbody>
              {visible.map((job) => {
                const canCancel = job.status === 'QUEUED' || job.status === 'RUNNING';
                return (
                  <tr
                    key={job.id}
                    className="border-b border-border/40 last:border-0 hover:bg-elevated/40"
                  >
                    <td className="px-5 py-3">
                      <span className="font-mono text-xs text-foreground" title={job.id}>
                        {job.id.length > 18 ? `${job.id.slice(0, 8)}…${job.id.slice(-6)}` : job.id}
                      </span>
                      {job.parent ? (
                        <div className="text-[11px] text-subtle" title={`Parent ${job.parent}`}>
                          child of {job.parent.slice(0, 8)}…
                        </div>
                      ) : null}
                    </td>
                    <td className="px-5 py-3">
                      <span className={cn('badge', toneClasses(jobStatusTone(job.status)))}>
                        {job.status}
                      </span>
                      {job.attempt > 1 ? (
                        <span className="ml-2 text-[11px] text-subtle">attempt {job.attempt}</span>
                      ) : null}
                    </td>
                    <td className="px-5 py-3 tabular-nums text-muted">
                      {formatDuration(jobDuration(job))}
                    </td>
                    <td className="px-5 py-3 font-mono text-xs text-muted">
                      {job.node ? job.node.slice(0, 12) : '—'}
                    </td>
                    <td
                      className="px-5 py-3 text-xs text-muted"
                      title={formatDateTime(job.createdAt)}
                    >
                      {formatDateTime(job.createdAt)}
                    </td>
                    <td className="px-5 py-3 text-right">
                      <button
                        type="button"
                        disabled={!canCancel || busy[job.id]}
                        onClick={() => void cancel(job)}
                        className="btn btn-danger ml-auto"
                        title={canCancel ? 'Cancel job' : 'Job is not cancellable'}
                      >
                        {busy[job.id] ? (
                          <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
                        ) : (
                          <Ban className="h-4 w-4" aria-hidden />
                        )}
                        Cancel
                      </button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
