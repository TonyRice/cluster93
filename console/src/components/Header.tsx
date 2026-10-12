'use client';

import { Loader2, LogOut, RefreshCw, Wifi, WifiOff, Zap } from 'lucide-react';
import { cn } from '@/lib/utils';
import type { ConsoleRole } from './Sidebar';

export type ConnectionStatus = 'connected' | 'connecting' | 'error';

interface HeaderProps {
  status: ConnectionStatus;
  role: ConsoleRole;
  /** The tenant name, or the operator identity label. */
  identity: string;
  /** The node serving the console, from `/health`. */
  node?: string | null;
  onRefresh?: () => void;
  onLogout: () => void;
  refreshing?: boolean;
}

const STATUS_META: Record<ConnectionStatus, { label: string; className: string }> = {
  connected: {
    label: 'Connected',
    className: 'border-positive/40 bg-positive/10 text-positive',
  },
  connecting: {
    label: 'Connecting',
    className: 'border-warning/40 bg-warning/10 text-warning',
  },
  error: {
    label: 'Disconnected',
    className: 'border-danger/40 bg-danger/10 text-danger',
  },
};

/** Top bar: brand, live connection badge, scope badge, refresh, and sign out. */
export function Header({
  status,
  role,
  identity,
  node,
  onRefresh,
  onLogout,
  refreshing = false,
}: HeaderProps) {
  const meta = STATUS_META[status];

  return (
    <header className="flex h-16 shrink-0 items-center justify-between gap-4 border-b border-border/70 bg-surface/60 px-6 backdrop-blur">
      <div className="flex items-center gap-3">
        <div className="flex h-9 w-9 items-center justify-center rounded-lg border border-accent/30 bg-accent/10 text-accent">
          <Zap className="h-5 w-5" aria-hidden />
        </div>
        <div className="leading-tight">
          <div className="text-sm font-semibold tracking-tight">Automately</div>
          <div className="text-xs text-subtle">Management Console</div>
        </div>
      </div>

      <div className="flex items-center gap-3">
        {node ? (
          <span className="hidden font-mono text-xs text-subtle sm:inline">node: {node}</span>
        ) : null}

        <span className={cn('badge', meta.className)} title={`API connection: ${meta.label}`}>
          {status === 'connected' ? (
            <Wifi className="h-3.5 w-3.5" aria-hidden />
          ) : status === 'connecting' ? (
            <Loader2 className="h-3.5 w-3.5 animate-spin" aria-hidden />
          ) : (
            <WifiOff className="h-3.5 w-3.5" aria-hidden />
          )}
          {meta.label}
        </span>

        <span
          className={cn(
            'badge',
            role === 'operator'
              ? 'border-accent/40 bg-accent/10 text-accent'
              : 'border-border/80 bg-elevated text-muted',
          )}
          title={role === 'operator' ? 'System / Cluster level' : 'Tenant level'}
        >
          {role === 'operator' ? 'Operator' : identity || 'Tenant'}
        </span>

        {onRefresh ? (
          <button
            type="button"
            onClick={onRefresh}
            disabled={refreshing}
            className="btn btn-ghost"
            title="Refresh"
            aria-label="Refresh"
          >
            <RefreshCw className={cn('h-4 w-4', refreshing && 'animate-spin')} aria-hidden />
          </button>
        ) : null}

        <button type="button" onClick={onLogout} className="btn btn-ghost" title="Sign out">
          <LogOut className="h-4 w-4" aria-hidden />
          <span className="hidden sm:inline">Sign out</span>
        </button>
      </div>
    </header>
  );
}
