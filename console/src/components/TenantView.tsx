'use client';

import { useCallback, useEffect, useState } from 'react';
import {
  Activity,
  Database,
  FileText,
  ListChecks,
  Loader2,
  Rocket,
  ScrollText,
  ShieldAlert,
  TriangleAlert,
} from 'lucide-react';
import { api, ApiError } from '@/lib/api';
import { cn, formatBytes } from '@/lib/utils';
import type { TenantTab } from './Sidebar';
import { JobsTab } from './JobsTab';
import { ServicesTab } from './ServicesTab';
import { AppsTab } from './AppsTab';
import { FilesTab } from './FilesTab';
import { MembersTab } from './MembersTab';

interface TenantViewProps {
  tenant: string;
  activeTab: TenantTab;
  refreshKey: number;
}

/** The tenant-level dashboard: an overview plus the jobs/services/apps/files views. */
export function TenantView({ tenant, activeTab, refreshKey }: TenantViewProps) {
  switch (activeTab) {
    case 'jobs':
      return <JobsTab refreshKey={refreshKey} />;
    case 'services':
      return <ServicesTab refreshKey={refreshKey} />;
    case 'apps':
      return <AppsTab refreshKey={refreshKey} />;
    case 'files':
      return <FilesTab refreshKey={refreshKey} />;
    case 'members':
      return <MembersTab refreshKey={refreshKey} />;
    case 'audit':
      return <AuditPlaceholder />;
    case 'overview':
    default:
      return <TenantOverview tenant={tenant} refreshKey={refreshKey} />;
  }
}

interface OverviewCounts {
  jobs: number;
  runningJobs: number;
  services: number;
  servicesRunning: number;
  apps: number;
  files: number;
  fileBytes: number;
}

function TenantOverview({ tenant, refreshKey }: { tenant: string; refreshKey: number }) {
  const [counts, setCounts] = useState<OverviewCounts | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async (signal?: AbortSignal) => {
    setLoading(true);
    try {
      const [jobs, services, apps, files] = await Promise.all([
        api.getJobs(signal),
        api.getServices(signal),
        api.getApps(signal),
        api.getFiles(signal),
      ]);
      setCounts({
        jobs: jobs.jobs.length,
        runningJobs: jobs.jobs.filter((job) => job.status === 'RUNNING').length,
        services: services.services.length,
        servicesRunning: services.services.filter(
          (service) => service.status.toUpperCase() === 'RUNNING',
        ).length,
        apps: apps.apps.length,
        files: files.files.length,
        fileBytes: files.files.reduce((sum, file) => sum + (file.size || 0), 0),
      });
      setError(null);
    } catch (cause) {
      if (cause instanceof DOMException && cause.name === 'AbortError') return;
      setError(cause instanceof ApiError ? cause.message : 'Failed to load the overview.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    void load(controller.signal);
    return () => controller.abort();
  }, [load, refreshKey]);

  if (loading) {
    return (
      <div className="flex items-center justify-center gap-2 p-12 text-sm text-muted">
        <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
        Loading tenant overview…
      </div>
    );
  }

  if (error || !counts) {
    return (
      <div className="panel p-6">
        <div className="flex items-start gap-3 rounded-lg border border-danger/40 bg-danger/10 p-4 text-sm text-danger">
          <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
          <span className="flex-1">{error ?? 'No data.'}</span>
          <button type="button" onClick={() => void load()} className="btn btn-ghost">
            Retry
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <div className="panel p-5">
        <h2 className="text-lg font-semibold tracking-tight">Tenant {tenant}</h2>
        <p className="mt-1 text-sm text-muted">
          Everything you own on this cluster: durable jobs, supervised services, apps, and the
          virtual file system.
        </p>
      </div>

      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard
          icon={ListChecks}
          label="Jobs"
          value={counts.jobs}
          hint={`${counts.runningJobs} running`}
        />
        <StatCard
          icon={Activity}
          label="Services"
          value={counts.services}
          hint={`${counts.servicesRunning} running`}
        />
        <StatCard icon={Rocket} label="Apps" value={counts.apps} hint="deployed" />
        <StatCard
          icon={FileText}
          label="Files"
          value={counts.files}
          hint={formatBytes(counts.fileBytes)}
        />
      </div>

      <div className="grid gap-4 lg:grid-cols-2">
        <section className="panel p-5">
          <h3 className="flex items-center gap-2 text-sm font-semibold tracking-tight">
            <Database className="h-4 w-4 text-accent" aria-hidden />
            Storage
          </h3>
          <p className="mt-3 text-sm text-muted">
            {counts.files} object{counts.files === 1 ? '' : 's'} totalling{' '}
            <span className="text-foreground">{formatBytes(counts.fileBytes)}</span> in the tenant
            VFS.
          </p>
        </section>
        <section className="panel p-5">
          <h3 className="flex items-center gap-2 text-sm font-semibold tracking-tight">
            <ShieldAlert className="h-4 w-4 text-accent" aria-hidden />
            Isolation
          </h3>
          <p className="mt-3 text-sm text-muted">
            Your scripts run in sandboxed workers. Files, secrets, and job data stay within tenant{' '}
            <span className="font-mono text-foreground">{tenant}</span> and are never visible to
            operators.
          </p>
        </section>
      </div>
    </div>
  );
}

interface StatCardProps {
  icon: React.ComponentType<{ className?: string; 'aria-hidden'?: boolean }>;
  label: string;
  value: number;
  hint?: string;
}

function StatCard({ icon: Icon, label, value, hint }: StatCardProps) {
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

function AuditPlaceholder() {
  return (
    <section className="panel">
      <div className="panel-header">
        <h2 className="text-sm font-semibold tracking-tight">Audit Log</h2>
      </div>
      <div
        className={cn(
          'flex flex-col items-center justify-center gap-3 p-12 text-center text-sm text-subtle',
        )}
      >
        <ScrollText className="h-6 w-6" aria-hidden />
        <p className="max-w-md">
          The tamper-evident audit chain is queryable at{' '}
          <code className="rounded bg-elevated px-1.5 py-0.5 font-mono text-xs text-foreground">
            GET /v1/audit
          </code>
          . The chain browser ships in a later phase; use the CLI or API until then.
        </p>
      </div>
    </section>
  );
}
