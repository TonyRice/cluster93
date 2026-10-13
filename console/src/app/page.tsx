'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { Loader2, RefreshCw, TriangleAlert } from 'lucide-react';
import { api, ApiError, clearToken, getToken, onUnauthorized, setTargetTenant, setToken } from '@/lib/api';
import type { Health, Tenant, Whoami } from '@/lib/types';
import { Header, type ConnectionStatus } from '@/components/Header';
import { LoginView } from '@/components/LoginView';
import { OperatorView } from '@/components/OperatorView';
import { TenantView } from '@/components/TenantView';
import {
  Sidebar,
  type ConsoleRole,
  type OperatorTab,
  type TenantTab,
} from '@/components/Sidebar';

type Phase = 'boot' | 'login' | 'connecting' | 'ready' | 'error';

const HEALTH_POLL_MS = 15_000;

/**
 * The console's single route. It owns authentication state (a bearer token in
 * `localStorage` validated against `/v1/whoami`) and switches between the
 * operator and tenant views based on the token's identity. All other pages are
 * client-side tabs rendered under this shell, so the static export stays a SPA.
 */
export default function ConsolePage() {
  const [phase, setPhase] = useState<Phase>('boot');
  const [whoami, setWhoami] = useState<Whoami | null>(null);
  const [health, setHealth] = useState<Health | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [activeTab, setActiveTab] = useState<string>('overview');
  const [refreshKey, setRefreshKey] = useState(0);
  const [refreshing, setRefreshing] = useState(false);
  const [selectedTenant, setSelectedTenant] = useState<string | null>(null);
  const [tenants, setTenants] = useState<Tenant[]>([]);

  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  // Any 401 anywhere in the app clears the token and returns to the login card.
  useEffect(() => {
    return onUnauthorized(() => {
      if (!mounted.current) return;
      setTargetTenant(null);
      setSelectedTenant(null);
      setWhoami(null);
      setError('Your token was rejected. Please sign in again.');
      setPhase('login');
    });
  }, []);

  const pollHealth = useCallback(async () => {
    try {
      const result = await api.getHealth();
      if (mounted.current) setHealth(result);
    } catch {
      if (mounted.current) setHealth(null);
    }
  }, []);

  useEffect(() => {
    void pollHealth();
    const timer = window.setInterval(() => void pollHealth(), HEALTH_POLL_MS);
    return () => window.clearInterval(timer);
  }, [pollHealth]);

  // Operators list the cluster's tenants for the context switcher.
  useEffect(() => {
    if (!whoami?.admin) {
      setTenants([]);
      return;
    }
    const controller = new AbortController();
    api
      .getTenants(controller.signal)
      .then(({ tenants: list }) => {
        if (mounted.current) setTenants([...list].sort((a, b) => a.name.localeCompare(b.name)));
      })
      .catch(() => {
        /* the operator tenants section surfaces a readable error instead */
      });
    return () => controller.abort();
  }, [whoami?.admin, refreshKey]);

  /** Validates the stored/entered token and resolves the operator/tenant role. */
  const connect = useCallback(async () => {
    setError(null);
    setPhase('connecting');
    try {
      const identity = await api.whoami();
      if (!mounted.current) return;
      setWhoami(identity);
      setSelectedTenant(null);
      setTargetTenant(null);
      setActiveTab('overview');
      setRefreshKey((key) => key + 1);
      setPhase('ready');
    } catch (cause) {
      if (!mounted.current) return;
      if (cause instanceof ApiError && cause.status === 401) {
        setPhase('login');
        return;
      }
      setError(cause instanceof ApiError ? cause.message : 'Could not reach the cluster.');
      setPhase('error');
    }
  }, []);

  // Boot: connect automatically when a token is already stored.
  useEffect(() => {
    if (getToken()) {
      void connect();
    } else {
      setPhase('login');
    }
  }, [connect]);

  const handleLogin = useCallback(
    async (token: string) => {
      setToken(token);
      await connect();
    },
    [connect],
  );

  const handleLogout = useCallback(() => {
    clearToken();
    setTargetTenant(null);
    setSelectedTenant(null);
    setWhoami(null);
    setError(null);
    setPhase('login');
  }, []);

  const handleSelectTenant = useCallback((tenant: string | null) => {
    setTargetTenant(tenant);
    setSelectedTenant(tenant);
    setActiveTab('overview');
    setRefreshKey((key) => key + 1);
  }, []);

  const handleRefresh = useCallback(() => {
    setRefreshing(true);
    setRefreshKey((key) => key + 1);
    void pollHealth().finally(() => setRefreshing(false));
  }, [pollHealth]);

  const isAdmin = Boolean(whoami?.admin);
  // The header keeps the operator identity (and its context switcher) for admins,
  // while the sidebar reflects the active context: cluster or a tenant workspace.
  const headerRole: ConsoleRole = isAdmin ? 'operator' : 'tenant';
  const contextRole: ConsoleRole = isAdmin && !selectedTenant ? 'operator' : 'tenant';
  const status: ConnectionStatus =
    phase === 'ready' ? 'connected' : phase === 'error' ? 'error' : 'connecting';

  if (phase === 'boot') {
    return (
      <div className="flex min-h-screen items-center justify-center gap-2 text-sm text-muted">
        <Loader2 className="h-5 w-5 animate-spin" aria-hidden />
        Starting console…
      </div>
    );
  }

  if (phase === 'login' || phase === 'connecting') {
    return (
      <LoginView
        onSubmit={handleLogin}
        error={error}
        loading={phase === 'connecting'}
        node={health?.node}
        online={Boolean(health)}
      />
    );
  }

  if (phase === 'error' || !whoami) {
    return (
      <div className="flex min-h-screen items-center justify-center p-6">
        <div className="panel w-full max-w-md p-6 text-center">
          <TriangleAlert className="mx-auto h-8 w-8 text-danger" aria-hidden />
          <h1 className="mt-3 text-lg font-semibold tracking-tight">Cannot reach the cluster</h1>
          <p className="mt-2 text-sm text-muted">
            {error ?? 'The Cluster93 API did not respond.'}
          </p>
          <div className="mt-5 flex justify-center gap-2">
            <button type="button" onClick={() => void connect()} className="btn btn-primary">
              <RefreshCw className="h-4 w-4" aria-hidden />
              Retry
            </button>
            <button type="button" onClick={handleLogout} className="btn btn-ghost">
              Sign in with another token
            </button>
          </div>
        </div>
      </div>
    );
  }

  const identity = whoami.admin ? (whoami.user ?? 'admin') : (whoami.tenant ?? 'tenant');

  return (
    <>
      <Header
        status={status}
        role={headerRole}
        identity={identity}
        node={health?.node}
        onRefresh={handleRefresh}
        refreshing={refreshing}
        onLogout={handleLogout}
        selectedTenant={isAdmin ? selectedTenant : null}
        tenants={tenants}
        onSelectTenant={isAdmin ? handleSelectTenant : undefined}
      />
      <div className="flex flex-1 overflow-hidden">
        <Sidebar
          role={contextRole}
          activeTab={activeTab}
          onSelect={setActiveTab}
          onLogout={handleLogout}
        />
        <main className="flex-1 overflow-y-auto p-6">
          <div className="mx-auto w-full max-w-7xl">
            {isAdmin && selectedTenant !== null ? (
              <TenantView
                tenant={selectedTenant}
                activeTab={(activeTab as TenantTab) ?? 'overview'}
                refreshKey={refreshKey}
              />
            ) : isAdmin ? (
              <OperatorView
                whoami={whoami}
                activeTab={(activeTab as OperatorTab) ?? 'overview'}
                refreshKey={refreshKey}
                health={health}
                onSelectTenant={handleSelectTenant}
              />
            ) : (
              <TenantView
                tenant={whoami.tenant ?? 'tenant'}
                activeTab={(activeTab as TenantTab) ?? 'overview'}
                refreshKey={refreshKey}
              />
            )}
          </div>
        </main>
      </div>
    </>
  );
}
