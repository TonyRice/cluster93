'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { Loader2, RefreshCw, TriangleAlert } from 'lucide-react';
import { api, ApiError, clearToken, getToken, onUnauthorized, setToken } from '@/lib/api';
import type { Health, Whoami } from '@/lib/types';
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

  /** Validates the stored/entered token and resolves the operator/tenant role. */
  const connect = useCallback(async () => {
    setError(null);
    setPhase('connecting');
    try {
      const identity = await api.whoami();
      if (!mounted.current) return;
      setWhoami(identity);
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
    setWhoami(null);
    setError(null);
    setPhase('login');
  }, []);

  const handleRefresh = useCallback(() => {
    setRefreshing(true);
    setRefreshKey((key) => key + 1);
    void pollHealth().finally(() => setRefreshing(false));
  }, [pollHealth]);

  const role: ConsoleRole = whoami?.admin ? 'operator' : 'tenant';
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
            {error ?? 'The Automately API did not respond.'}
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
        role={role}
        identity={identity}
        node={health?.node}
        onRefresh={handleRefresh}
        refreshing={refreshing}
        onLogout={handleLogout}
      />
      <div className="flex flex-1 overflow-hidden">
        <Sidebar
          role={role}
          activeTab={activeTab}
          onSelect={setActiveTab}
          onLogout={handleLogout}
        />
        <main className="flex-1 overflow-y-auto p-6">
          <div className="mx-auto w-full max-w-7xl">
            {role === 'operator' ? (
              <OperatorView
                whoami={whoami}
                activeTab={(activeTab as OperatorTab) ?? 'overview'}
                refreshKey={refreshKey}
                health={health}
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
