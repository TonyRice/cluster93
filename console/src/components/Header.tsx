'use client';

import { useEffect, useRef, useState } from 'react';
import {
  Boxes,
  Check,
  ChevronDown,
  Loader2,
  LogOut,
  RefreshCw,
  Server,
  Wifi,
  WifiOff,
  Zap,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import type { Tenant } from '@/lib/types';
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
  /** The tenant workspace an operator is inspecting, or `null` for the cluster. */
  selectedTenant?: string | null;
  /** Cluster tenants, shown in the operator's context switcher. */
  tenants?: Tenant[];
  /** Switch the operator's tenant context (`null` returns to the cluster view). */
  onSelectTenant?: (tenant: string | null) => void;
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
  selectedTenant = null,
  tenants = [],
  onSelectTenant,
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

        {role === 'operator' && onSelectTenant ? (
          <TenantSwitcher
            selectedTenant={selectedTenant ?? null}
            tenants={tenants}
            onSelect={onSelectTenant}
          />
        ) : null}

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

/**
 * Operator-only dropdown for picking the active tenant context: the cluster
 * itself (infrastructure only) or one of the cluster's tenant workspaces.
 */
function TenantSwitcher({
  selectedTenant,
  tenants,
  onSelect,
}: {
  selectedTenant: string | null;
  tenants: Tenant[];
  onSelect: (tenant: string | null) => void;
}) {
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    function onPointerDown(event: MouseEvent) {
      if (rootRef.current && !rootRef.current.contains(event.target as Node)) {
        setOpen(false);
      }
    }
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') setOpen(false);
    }
    document.addEventListener('mousedown', onPointerDown);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('mousedown', onPointerDown);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [open]);

  const inTenant = selectedTenant !== null;

  function choose(tenant: string | null) {
    onSelect(tenant);
    setOpen(false);
  }

  return (
    <div ref={rootRef} className="relative">
      <button
        type="button"
        onClick={() => setOpen((value) => !value)}
        className="btn btn-ghost"
        aria-haspopup="menu"
        aria-expanded={open}
        title={inTenant ? `Tenant workspace: ${selectedTenant}` : 'Cluster Infrastructure'}
      >
        {inTenant ? (
          <Boxes className="h-4 w-4 text-accent" aria-hidden />
        ) : (
          <Server className="h-4 w-4 text-accent" aria-hidden />
        )}
        <span className="hidden max-w-40 truncate sm:inline">
          {inTenant ? `Tenant: ${selectedTenant}` : 'Cluster'}
        </span>
        <ChevronDown
          className={cn('h-3.5 w-3.5 transition-transform', open && 'rotate-180')}
          aria-hidden
        />
      </button>

      {open ? (
        <div
          role="menu"
          className="absolute right-0 top-full z-30 mt-2 w-64 overflow-hidden rounded-xl border border-border/80 bg-surface shadow-panel"
        >
          <button
            type="button"
            role="menuitem"
            onClick={() => choose(null)}
            className={cn(
              'flex w-full items-center gap-3 px-3 py-2.5 text-left text-sm transition-colors',
              !inTenant ? 'bg-elevated text-foreground' : 'text-muted hover:bg-elevated/60',
            )}
          >
            <Server className="h-4 w-4 text-accent" aria-hidden />
            <span className="flex-1">Cluster Infrastructure</span>
            {!inTenant ? <Check className="h-4 w-4 text-accent" aria-hidden /> : null}
          </button>

          <div className="my-1 border-t border-border/70" />

          <div className="px-3 pb-1 pt-2 text-[11px] font-medium uppercase tracking-wider text-subtle">
            Tenant Workspace
          </div>
          {tenants.length === 0 ? (
            <div className="px-3 pb-3 text-xs text-subtle">No tenants yet.</div>
          ) : (
            <div className="max-h-64 overflow-y-auto pb-1">
              {tenants.map((tenant) => {
                const active = selectedTenant === tenant.name;
                return (
                  <button
                    key={tenant.name}
                    type="button"
                    role="menuitem"
                    onClick={() => choose(tenant.name)}
                    className={cn(
                      'flex w-full items-center gap-3 px-3 py-2 text-left text-sm transition-colors',
                      active ? 'bg-elevated text-foreground' : 'text-muted hover:bg-elevated/60',
                    )}
                  >
                    <Boxes className="h-4 w-4 shrink-0 text-subtle" aria-hidden />
                    <span className="flex-1 truncate font-mono text-xs">{tenant.name}</span>
                    {active ? <Check className="h-4 w-4 shrink-0 text-accent" aria-hidden /> : null}
                  </button>
                );
              })}
            </div>
          )}
        </div>
      ) : null}
    </div>
  );
}
