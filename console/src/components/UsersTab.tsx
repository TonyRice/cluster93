'use client';

import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Loader2, Plus, RefreshCw, ShieldCheck, TriangleAlert, UserPlus, X } from 'lucide-react';
import { api, ApiError, type Invite, type User } from '@/lib/api';
import { cn, formatDateTime, toneClasses } from '@/lib/utils';

interface UsersTabProps {
  /** Bumped by the shell to force a refetch. */
  refreshKey: number;
}

/**
 * Cluster-wide identity management (admin): every user on the cluster, plus the
 * open operator invites, which an operator can create or withdraw.
 */
export function UsersTab({ refreshKey }: UsersTabProps) {
  const [users, setUsers] = useState<User[]>([]);
  const [invites, setInvites] = useState<Invite[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [inviteUser, setInviteUser] = useState('');
  const [inviteNote, setInviteNote] = useState('');
  const [creating, setCreating] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [createdCode, setCreatedCode] = useState<string | null>(null);
  const [withdrawing, setWithdrawing] = useState<string | null>(null);

  const load = useCallback(async (signal?: AbortSignal) => {
    setLoading(true);
    try {
      const [found, open] = await Promise.all([api.getUsers(signal), api.getAdminInvites(signal)]);
      setUsers([...found.users].sort((a, b) => a.name.localeCompare(b.name)));
      setInvites(open.invites);
      setError(null);
    } catch (cause) {
      if (cause instanceof DOMException && cause.name === 'AbortError') return;
      setError(cause instanceof ApiError ? cause.message : 'Failed to load users and invites.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    void load(controller.signal);
    return () => controller.abort();
  }, [load, refreshKey]);

  async function createInvite(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (creating) return;
    setCreating(true);
    setFormError(null);
    setCreatedCode(null);
    try {
      const created = await api.createAdminInvite({
        ...(inviteUser.trim() ? { user: inviteUser.trim() } : {}),
        ...(inviteNote.trim() ? { note: inviteNote.trim() } : {}),
      });
      setInvites((prev) => [...prev, created]);
      setInviteUser('');
      setInviteNote('');
      setShowForm(false);
      setCreatedCode(created.code ?? null);
    } catch (cause) {
      setFormError(cause instanceof ApiError ? cause.message : 'Failed to create the invite.');
    } finally {
      setCreating(false);
    }
  }

  async function withdraw(invite: Invite) {
    setWithdrawing(invite.id);
    try {
      await api.revokeAdminInvite(invite.id);
      setInvites((prev) => prev.filter((entry) => entry.id !== invite.id));
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : 'Failed to withdraw the invite.');
    } finally {
      setWithdrawing(null);
    }
  }

  return (
    <div className="space-y-4">
      {error ? (
        <div className="flex items-start gap-3 rounded-lg border border-danger/40 bg-danger/10 p-4 text-sm text-danger">
          <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
          <span className="flex-1">{error}</span>
          <button type="button" onClick={() => void load()} className="btn btn-ghost">
            Retry
          </button>
        </div>
      ) : null}

      <section className="panel">
        <div className="panel-header">
          <div>
            <h2 className="text-sm font-semibold tracking-tight">Cluster Users</h2>
            <p className="text-xs text-subtle">Everyone with an account on this cluster.</p>
          </div>
          <button
            type="button"
            onClick={() => void load()}
            className="btn btn-ghost"
            aria-label="Reload users"
          >
            <RefreshCw className={cn('h-4 w-4', loading && 'animate-spin')} aria-hidden />
          </button>
        </div>

        {loading ? (
          <LoadingRow label="Loading users…" />
        ) : users.length === 0 ? (
          <EmptyRow label="No users yet." />
        ) : (
          <ul className="divide-y divide-border/40">
            {users.map((user) => (
              <li key={user.id} className="flex flex-wrap items-center gap-4 px-5 py-4">
                <div className="min-w-48 flex-1">
                  <div className="flex items-center gap-2">
                    <span className="font-medium text-foreground">{user.name}</span>
                    {user.operator ? (
                      <span className={cn('badge', toneClasses('accent'))}>operator</span>
                    ) : null}
                    {user.disabled ? (
                      <span className={cn('badge', toneClasses('danger'))}>disabled</span>
                    ) : null}
                  </div>
                  <div className="mt-1 text-xs text-subtle">
                    {user.email ?? user.id}
                  </div>
                </div>
                <div className="flex items-center gap-2 text-xs text-subtle">
                  {user.hasPassword ? 'password set' : 'no password'}
                  <span aria-hidden>·</span>
                  <span title={formatDateTime(user.createdAt)}>joined {formatDateTime(user.createdAt)}</span>
                </div>
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className="panel">
        <div className="panel-header">
          <div>
            <h2 className="text-sm font-semibold tracking-tight">Operator Invites</h2>
            <p className="text-xs text-subtle">Open invitations to become an operator.</p>
          </div>
          <button
            type="button"
            onClick={() => setShowForm((value) => !value)}
            className="btn btn-primary"
          >
            <UserPlus className="h-4 w-4" aria-hidden />
            Invite Operator
          </button>
        </div>

        {createdCode ? (
          <div className="border-b border-border/70 px-5 py-3">
            <div className="flex items-center gap-2 rounded-lg border border-accent/40 bg-accent/10 px-3 py-2 text-sm">
              <ShieldCheck className="h-4 w-4 shrink-0 text-accent" aria-hidden />
              <span>
                Invite code (shown once):{' '}
                <code className="font-mono text-accent">{createdCode}</code>
              </span>
            </div>
          </div>
        ) : null}

        {showForm ? (
          <form onSubmit={createInvite} className="space-y-3 border-b border-border/70 px-5 py-4">
            <div className="grid gap-3 sm:grid-cols-2">
              <div>
                <label htmlFor="invite-user" className="stat-label">
                  Username (optional)
                </label>
                <input
                  id="invite-user"
                  value={inviteUser}
                  onChange={(event) => setInviteUser(event.target.value)}
                  placeholder="who may redeem it"
                  autoFocus
                  spellCheck={false}
                  className="input mt-2"
                />
              </div>
              <div>
                <label htmlFor="invite-note" className="stat-label">
                  Note (optional)
                </label>
                <input
                  id="invite-note"
                  value={inviteNote}
                  onChange={(event) => setInviteNote(event.target.value)}
                  placeholder="what it is for"
                  className="input mt-2"
                />
              </div>
            </div>

            {formError ? (
              <div className="flex items-start gap-3 rounded-lg border border-danger/40 bg-danger/10 p-3 text-sm text-danger">
                <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
                <span>{formError}</span>
              </div>
            ) : null}

            <div className="flex items-center gap-2">
              <button type="submit" disabled={creating} className="btn btn-primary">
                {creating ? (
                  <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
                ) : (
                  <Plus className="h-4 w-4" aria-hidden />
                )}
                Create
              </button>
              <button type="button" onClick={() => setShowForm(false)} className="btn btn-ghost">
                Cancel
              </button>
            </div>
          </form>
        ) : null}

        {loading ? (
          <LoadingRow label="Loading invites…" />
        ) : invites.length === 0 ? (
          <EmptyRow label="No open operator invites." />
        ) : (
          <ul className="divide-y divide-border/40">
            {invites.map((invite) => (
              <li key={invite.id} className="flex flex-wrap items-center gap-4 px-5 py-4">
                <div className="min-w-48 flex-1">
                  <div className="font-medium text-foreground">{invite.user ?? 'anyone'}</div>
                  <div className="mt-1 text-xs text-subtle" title={formatDateTime(invite.expiresAt)}>
                    expires {formatDateTime(invite.expiresAt)}
                  </div>
                </div>
                <button
                  type="button"
                  onClick={() => void withdraw(invite)}
                  disabled={withdrawing === invite.id}
                  className="btn btn-ghost"
                >
                  {withdrawing === invite.id ? (
                    <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
                  ) : (
                    <X className="h-4 w-4" aria-hidden />
                  )}
                  Withdraw
                </button>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}

function LoadingRow({ label }: { label: string }) {
  return (
    <div className="flex items-center justify-center gap-2 p-12 text-sm text-muted">
      <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
      {label}
    </div>
  );
}

function EmptyRow({ label }: { label: string }) {
  return <div className="p-12 text-center text-sm text-subtle">{label}</div>;
}
