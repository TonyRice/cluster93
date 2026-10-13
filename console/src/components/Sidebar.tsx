'use client';

import {
  Activity,
  Boxes,
  CircleDot,
  Database,
  FileText,
  LayoutDashboard,
  ListChecks,
  LogOut,
  Rocket,
  Settings,
  ShieldAlert,
  Users,
  type LucideIcon,
} from 'lucide-react';
import { cn } from '@/lib/utils';

export type OperatorTab = 'overview' | 'nodes' | 'tenants' | 'storage' | 'settings';
export type TenantTab = 'overview' | 'jobs' | 'services' | 'apps' | 'files' | 'audit';
export type ConsoleRole = 'operator' | 'tenant';

interface NavItem<T extends string> {
  id: T;
  label: string;
  icon: LucideIcon;
}

const OPERATOR_NAV: NavItem<OperatorTab>[] = [
  { id: 'overview', label: 'Overview', icon: LayoutDashboard },
  { id: 'nodes', label: 'Cluster Nodes', icon: Boxes },
  { id: 'tenants', label: 'Tenants', icon: Users },
  { id: 'storage', label: 'Storage & Memory', icon: Database },
  { id: 'settings', label: 'Settings', icon: Settings },
];

const TENANT_NAV: NavItem<TenantTab>[] = [
  { id: 'overview', label: 'Overview', icon: LayoutDashboard },
  { id: 'jobs', label: 'Jobs', icon: ListChecks },
  { id: 'services', label: 'Services', icon: Activity },
  { id: 'apps', label: 'Apps', icon: Rocket },
  { id: 'files', label: 'Files', icon: FileText },
  { id: 'audit', label: 'Audit Log', icon: ShieldAlert },
];

interface SidebarProps {
  role: ConsoleRole;
  activeTab: string;
  onSelect: (tab: string) => void;
  onLogout: () => void;
}

/** Vertical navigation for the active view (operator or tenant). */
export function Sidebar({ role, activeTab, onSelect, onLogout }: SidebarProps) {
  const items: NavItem<string>[] = role === 'operator' ? OPERATOR_NAV : TENANT_NAV;

  return (
    <aside className="flex w-60 shrink-0 flex-col border-r border-border/70 bg-surface/60">
      <nav className="flex-1 space-y-1 p-3" aria-label="Primary">
        {items.map(({ id, label, icon: Icon }) => {
          const active = id === activeTab;
          return (
            <button
              key={id}
              type="button"
              onClick={() => onSelect(id)}
              aria-current={active ? 'page' : undefined}
              className={cn(
                'flex w-full items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition-colors',
                active
                  ? 'bg-elevated text-foreground shadow-inner ring-1 ring-border/80'
                  : 'text-muted hover:bg-elevated/60 hover:text-foreground',
              )}
            >
              <Icon
                className={cn('h-4 w-4', active ? 'text-accent' : 'text-subtle')}
                aria-hidden
              />
              {label}
            </button>
          );
        })}
      </nav>

      <div className="border-t border-border/70 p-3">
        <div className="mb-3 flex items-center gap-2 rounded-lg bg-elevated/40 px-3 py-2 text-xs text-subtle">
          <CircleDot className="h-3.5 w-3.5 text-accent" aria-hidden />
          {role === 'operator' ? 'System / Cluster scope' : 'Tenant scope'}
        </div>
        <button
          type="button"
          onClick={onLogout}
          className="flex w-full items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium text-muted transition-colors hover:bg-danger/10 hover:text-danger"
        >
          <LogOut className="h-4 w-4" aria-hidden />
          Sign out
        </button>
      </div>
    </aside>
  );
}
