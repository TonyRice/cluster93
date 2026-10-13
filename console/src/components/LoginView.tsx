'use client';

import { useState, type FormEvent } from 'react';
import { KeyRound, Loader2, Lock, ShieldCheck, Terminal, UserPlus } from 'lucide-react';
import { api, ApiError } from '@/lib/api';
import { cn } from '@/lib/utils';

interface LoginViewProps {
  /** Called with the bearer token to use. May reject; the caller renders the error. */
  onSubmit: (token: string) => void | Promise<void>;
  error?: string | null;
  loading?: boolean;
  /** The node id from `/health`, shown as a reassuring connectivity hint. */
  node?: string | null;
  online?: boolean;
}

type Mode = 'password' | 'redeem' | 'token';

/**
 * Sign-in card. By default it exchanges a username and password for a personal
 * session token (`POST /v1/auth/login`, D-133). A second mode redeems an invite
 * code to create a new account; an expandable option lets CLI / service-token
 * power users paste a bearer token directly. Passkeys and OIDC arrive in a
 * later phase. See `ideas/console.md` §2.
 */
export function LoginView({ onSubmit, error, loading = false, node, online }: LoginViewProps) {
  const [mode, setMode] = useState<Mode>('password');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [tenant, setTenant] = useState('');
  const [token, setToken] = useState('');
  const [inviteCode, setInviteCode] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [localError, setLocalError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const busy = loading || submitting;
  const shownError = error ?? localError;

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy) return;
    setLocalError(null);

    if (mode === 'token') {
      const trimmed = token.trim();
      if (!trimmed) return;
      await onSubmit(trimmed);
      return;
    }

    if (mode === 'redeem') {
      const code = inviteCode.trim();
      const name = username.trim();
      if (!code || !name || password.length === 0) return;
      if (password !== confirmPassword) {
        setLocalError('Passwords do not match.');
        return;
      }
      setSubmitting(true);
      try {
        const redeemed = await api.redeemInvite({ code, name, password });
        await onSubmit(redeemed.token);
      } catch (cause) {
        setLocalError(cause instanceof ApiError ? cause.message : 'Could not reach the cluster.');
      } finally {
        setSubmitting(false);
      }
      return;
    }

    const name = username.trim();
    if (!name || password.length === 0) return;

    setSubmitting(true);
    try {
      const signedIn = await api.login({
        username: name,
        password,
        ...(tenant.trim() ? { tenant: tenant.trim() } : {}),
      });
      await onSubmit(signedIn.token);
    } catch (cause) {
      setLocalError(cause instanceof ApiError ? cause.message : 'Could not reach the cluster.');
    } finally {
      setSubmitting(false);
    }
  }

  function switchMode(next: Mode) {
    setLocalError(null);
    setMode(next);
  }

  const canSubmit =
    mode === 'token'
      ? token.trim().length > 0
      : mode === 'redeem'
        ? inviteCode.trim().length > 0 &&
          username.trim().length > 0 &&
          password.length > 0 &&
          confirmPassword.length > 0
        : username.trim().length > 0 && password.length > 0;

  const subtitle =
    mode === 'password'
      ? 'Sign in with your username and password.'
      : mode === 'redeem'
        ? 'Redeem an invite code to create your account.'
        : 'Sign in with an Automately bearer token.';

  return (
    <div className="flex min-h-screen items-center justify-center p-6">
      <div className="w-full max-w-md">
        <div className="mb-8 flex flex-col items-center gap-3 text-center">
          <div className="flex h-12 w-12 items-center justify-center rounded-xl border border-accent/30 bg-accent/10 text-accent">
            <ShieldCheck className="h-6 w-6" aria-hidden />
          </div>
          <div>
            <h1 className="text-xl font-semibold tracking-tight">Automately Console</h1>
            <p className="mt-1 text-sm text-muted">{subtitle}</p>
          </div>
        </div>

        <form onSubmit={handleSubmit} className="panel p-6">
          {mode === 'password' ? (
            <div className="space-y-4">
              <div>
                <label htmlFor="username" className="stat-label">
                  Username
                </label>
                <input
                  id="username"
                  name="username"
                  type="text"
                  autoComplete="username"
                  spellCheck={false}
                  autoFocus
                  value={username}
                  onChange={(event) => setUsername(event.target.value)}
                  placeholder="ada"
                  className="input mt-2"
                />
              </div>

              <div>
                <label htmlFor="password" className="stat-label">
                  Password
                </label>
                <div className="relative mt-2">
                  <Lock
                    className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-subtle"
                    aria-hidden
                  />
                  <input
                    id="password"
                    name="password"
                    type="password"
                    autoComplete="current-password"
                    value={password}
                    onChange={(event) => setPassword(event.target.value)}
                    placeholder="••••••••"
                    className="input pl-9"
                  />
                </div>
              </div>

              <div>
                <label htmlFor="tenant" className="stat-label">
                  Tenant (optional)
                </label>
                <input
                  id="tenant"
                  name="tenant"
                  type="text"
                  spellCheck={false}
                  value={tenant}
                  onChange={(event) => setTenant(event.target.value)}
                  placeholder="Defaults to your first tenant"
                  className="input mt-2"
                />
              </div>
            </div>
          ) : mode === 'redeem' ? (
            <div className="space-y-4">
              <div>
                <label htmlFor="invite-code" className="stat-label">
                  Invite Code
                </label>
                <input
                  id="invite-code"
                  name="invite-code"
                  type="text"
                  autoComplete="off"
                  spellCheck={false}
                  autoFocus
                  value={inviteCode}
                  onChange={(event) => setInviteCode(event.target.value)}
                  placeholder="Paste the code you were sent"
                  className="input mt-2 font-mono"
                />
              </div>

              <div>
                <label htmlFor="username" className="stat-label">
                  Username
                </label>
                <input
                  id="username"
                  name="username"
                  type="text"
                  autoComplete="username"
                  spellCheck={false}
                  value={username}
                  onChange={(event) => setUsername(event.target.value)}
                  placeholder="ada"
                  className="input mt-2"
                />
              </div>

              <div>
                <label htmlFor="password" className="stat-label">
                  Password
                </label>
                <input
                  id="password"
                  name="password"
                  type="password"
                  autoComplete="new-password"
                  value={password}
                  onChange={(event) => setPassword(event.target.value)}
                  placeholder="••••••••"
                  className="input mt-2"
                />
              </div>

              <div>
                <label htmlFor="confirm-password" className="stat-label">
                  Confirm Password
                </label>
                <input
                  id="confirm-password"
                  name="confirm-password"
                  type="password"
                  autoComplete="new-password"
                  value={confirmPassword}
                  onChange={(event) => setConfirmPassword(event.target.value)}
                  placeholder="••••••••"
                  className="input mt-2"
                />
              </div>
            </div>
          ) : (
            <div>
              <label htmlFor="token" className="stat-label">
                Bearer token
              </label>
              <div className="relative mt-2">
                <KeyRound
                  className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-subtle"
                  aria-hidden
                />
                <input
                  id="token"
                  name="token"
                  type="password"
                  autoComplete="off"
                  spellCheck={false}
                  autoFocus
                  value={token}
                  onChange={(event) => setToken(event.target.value)}
                  placeholder="atly_…"
                  className="input pl-9 font-mono"
                />
              </div>
            </div>
          )}

          {shownError ? (
            <p
              role="alert"
              className="mt-3 rounded-lg border border-danger/40 bg-danger/10 px-3 py-2 text-sm text-danger"
            >
              {shownError}
            </p>
          ) : null}

          <button type="submit" disabled={busy || !canSubmit} className="btn btn-primary mt-4 w-full py-2">
            {busy ? (
              <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
            ) : (
              <Lock className="h-4 w-4" aria-hidden />
            )}
            {busy ? 'Verifying…' : mode === 'password' ? 'Sign in' : mode === 'redeem' ? 'Redeem invite' : 'Connect'}
          </button>

          <div className="mt-6 space-y-3 border-t border-border/80 pt-5 text-sm text-muted">
            <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
              {mode !== 'password' ? (
                <button
                  type="button"
                  onClick={() => switchMode('password')}
                  className="inline-flex items-center gap-2 font-medium text-accent hover:underline"
                >
                  <Lock className="h-4 w-4 shrink-0" aria-hidden />
                  Sign in
                </button>
              ) : null}
              {mode !== 'redeem' ? (
                <button
                  type="button"
                  onClick={() => switchMode('redeem')}
                  className="inline-flex items-center gap-2 font-medium text-accent hover:underline"
                >
                  <UserPlus className="h-4 w-4 shrink-0" aria-hidden />
                  Redeem an invite
                </button>
              ) : null}
              {mode !== 'token' ? (
                <button
                  type="button"
                  onClick={() => switchMode('token')}
                  className="inline-flex items-center gap-2 font-medium text-accent hover:underline"
                >
                  <Terminal className="h-4 w-4 shrink-0" aria-hidden />
                  Use API Token instead
                </button>
              ) : null}
            </div>
            <p className="text-xs text-subtle">
              {mode === 'password'
                ? 'Your session token is stored only in this browser’s localStorage and sent exclusively to this cluster.'
                : mode === 'redeem'
                  ? 'Redeeming creates your account (or joins you to a tenant) and signs you straight in.'
                  : 'Mint a tenant token with automately tokens create, or sign in with the admin token for operator access.'}
            </p>
          </div>
        </form>

        <p className="mt-6 flex items-center justify-center gap-2 text-xs text-subtle">
          <span
            className={cn('h-1.5 w-1.5 rounded-full', online ? 'bg-positive' : 'bg-subtle')}
            aria-hidden
          />
          {online ? `Cluster reachable${node ? ` · node ${node}` : ''}` : 'Cluster status unknown'}
        </p>
      </div>
    </div>
  );
}
