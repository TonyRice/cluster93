'use client';

import { useState, type FormEvent } from 'react';
import { KeyRound, Loader2, Lock, ShieldCheck, Terminal } from 'lucide-react';
import { cn } from '@/lib/utils';

interface LoginViewProps {
  /** Called with the trimmed token. May reject; the caller renders the error. */
  onSubmit: (token: string) => void | Promise<void>;
  error?: string | null;
  loading?: boolean;
  /** The node id from `/health`, shown as a reassuring connectivity hint. */
  node?: string | null;
  online?: boolean;
}

/**
 * Sign-in card. The console authenticates with a bearer token (personal token
 * or the admin token) rather than browser sessions; passkeys/OIDC arrive in a
 * later phase. See `ideas/console.md` §2.
 */
export function LoginView({ onSubmit, error, loading = false, node, online }: LoginViewProps) {
  const [token, setToken] = useState('');

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const trimmed = token.trim();
    if (!trimmed || loading) return;
    void onSubmit(trimmed);
  }

  return (
    <div className="flex min-h-screen items-center justify-center p-6">
      <div className="w-full max-w-md">
        <div className="mb-8 flex flex-col items-center gap-3 text-center">
          <div className="flex h-12 w-12 items-center justify-center rounded-xl border border-accent/30 bg-accent/10 text-accent">
            <ShieldCheck className="h-6 w-6" aria-hidden />
          </div>
          <div>
            <h1 className="text-xl font-semibold tracking-tight">Automately Console</h1>
            <p className="mt-1 text-sm text-muted">
              Sign in with an Automately bearer token.
            </p>
          </div>
        </div>

        <form onSubmit={handleSubmit} className="panel p-6">
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

          {error ? (
            <p className="mt-3 rounded-lg border border-danger/40 bg-danger/10 px-3 py-2 text-sm text-danger">
              {error}
            </p>
          ) : null}

          <button
            type="submit"
            disabled={loading || token.trim().length === 0}
            className="btn btn-primary mt-4 w-full py-2"
          >
            {loading ? (
              <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
            ) : (
              <Lock className="h-4 w-4" aria-hidden />
            )}
            {loading ? 'Verifying…' : 'Connect'}
          </button>

          <div className="mt-6 space-y-3 border-t border-border/80 pt-5 text-sm text-muted">
            <p className="flex items-start gap-2">
              <Terminal className="mt-0.5 h-4 w-4 shrink-0 text-subtle" aria-hidden />
              <span>
                Mint a tenant token with{' '}
                <code className="rounded bg-elevated px-1.5 py-0.5 font-mono text-xs text-foreground">
                  automately tokens create
                </code>{' '}
                or sign in with the admin token for operator access.
              </span>
            </p>
            <p className="text-xs text-subtle">
              The token is stored only in this browser&rsquo;s <code>localStorage</code> and sent
              exclusively to this cluster.
            </p>
          </div>
        </form>

        <p className="mt-6 flex items-center justify-center gap-2 text-xs text-subtle">
          <span
            className={cn(
              'h-1.5 w-1.5 rounded-full',
              online ? 'bg-positive' : 'bg-subtle',
            )}
            aria-hidden
          />
          {online ? `Cluster reachable${node ? ` · node ${node}` : ''}` : 'Cluster status unknown'}
        </p>
      </div>
    </div>
  );
}
