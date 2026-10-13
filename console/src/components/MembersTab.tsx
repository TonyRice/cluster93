'use client';

import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Loader2, Plus, RefreshCw, ShieldCheck, TriangleAlert, UserPlus, X } from 'lucide-react';
import { api, ApiError, type Invite, type Member } from '@/lib/api';
import { cn, formatDateTime, toneClasses } from '@/lib/utils';

interface MembersTabProps {
  /** Bumped by the shell to force a refetch. */
  refreshKey: number;
}

const ROLES = ['owner', 'developer', 'viewer'] as const;

/**
 * Tenant membership management: the tenant's members with their roles and
 * groups, and the open invites, which a member can create or withdraw.
 */
export function MembersTab({ refreshKey }: MembersTabProps) {
  const [members, setMembers] = useState<Member[]>([]);
  const [invites, setInvites] = useState<Invite[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [role, setRole] = useState<(typeof ROLES)[number]>('developer');
  const [inviteUser, setInviteUser] = useState('');
  const [creating, setCreating] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [createdCode, setCreatedCode] = useState<string | null>(null);
  const [withdrawing, setWithdrawing] = useState<string | null>(null);

  const load = useCallback(async (signal?: AbortSignal) => {
    setLoading(true);
    try {
      const [found, open] = await Promise.all([api.getMembers(signal), api.getTenantInvites(signal)]);
      setMembers([...found.members].sort((a, b) => (a.user ?? '').localeCompare(b.user ?? '')));
      setInvites(open.invites);
      setError(null);
    } catch (cause) {
      if (cause instanceof DOMException && cause.name === 'AbortError') return;
      setError(cause instanceof ApiError ? cause.message : 'Failed to load members and invites.');
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
      const created = await api.createTenantInvite({
        roles: [role],
        ...(inviteUser.trim() ? { user: inviteUser.trim() } : {}),
      });
      setInvites((prev) => [...prev, created]);
      setInviteUser('');
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
      await api.revokeTenantInvite(invite.id);
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
            <h2 className="text-sm font-semibold tracking-tight">Members</h2>
            <p className="text-xs text-subtle">Everyone in this tenant, with their roles and groups.</p>
          </div>
          <button
            type="button"
            onClick={() => void load()}
            className="btn btn-ghost"
            aria-label="Reload members"
          >
            <RefreshCw className={cn('h-4 w-4', loading && 'animate-spin')} aria-hidden />
          </button>
        </div>

        {loading ? (
          <LoadingRow label="Loading members…" />
        ) : members.length === 0 ? (
          <EmptyRow label="No members yet." />
        ) : (
          <ul className="divide-y divide-border/40">
            {members.map((member) => (
              <li key={member.userId ?? member.user} className="flex flex-wrap items-center gap-4 px-5 py-4">
                <div className="min-w-48 flex-1">
                  <div className="flex items-center gap-2">
                    <span className="font-medium text-foreground">{member.user ?? '—'}</span>
                    {member.owner ? (
                      <span className={cn('badge', toneClasses('accent'))}>owner</span>
                    ) : null}
                  </div>
                  <div className="mt-1 flex flex-wrap gap-1.5">
                    {member.roles
                      .filter((name) => name !== 'owner')
                      .map((name) => (
                        <span
                          key={name}
                          className="rounded-md border border-border/70 bg-background/50 px-2 py-0.5 font-mono text-[11px] text-muted"
                        >
                          {name}
                        </span>
                      ))}
                    {member.groups.map((name) => (
                      <span
                        key={`g-${name}`}
                        className="rounded-md border border-border/70 bg-elevated/40 px-2 py-0.5 font-mono text-[11px] text-subtle"
                      >
                        {name}
                      </span>
                    ))}
                  </div>
                </div>
                <div className="text-xs text-subtle" title={formatDateTime(member.joinedAt)}>
                  joined {formatDateTime(member.joinedAt)}
                </div>
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className="panel">
        <div className="panel-header">
          <div>
            <h2 className="text-sm font-semibold tracking-tight">Invites</h2>
            <p className="text-xs text-subtle">Open invitations to join this tenant.</p>
          </div>
          <button
            type="button"
            onClick={() => setShowForm((value) => !value)}
            className="btn btn-primary"
          >
            <UserPlus className="h-4 w-4" aria-hidden />
            Invite Member
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
                <label htmlFor="invite-role" className="stat-label">
                  Role
                </label>
                <select
                  id="invite-role"
                  value={role}
                  onChange={(event) => setRole(event.target.value as (typeof ROLES)[number])}
                  className="input mt-2"
                >
                  {ROLES.map((value) => (
                    <option key={value} value={value}>
                      {value}
                    </option>
                  ))}
                </select>
              </div>
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
          <EmptyRow label="No open invites." />
        ) : (
          <ul className="divide-y divide-border/40">
            {invites.map((invite) => (
              <li key={invite.id} className="flex flex-wrap items-center gap-4 px-5 py-4">
                <div className="min-w-48 flex-1">
                  <div className="flex items-center gap-2">
                    <span className="font-medium text-foreground">{invite.user ?? 'anyone'}</span>
                    {invite.roles.map((name) => (
                      <span
                        key={name}
                        className="rounded-md border border-border/70 bg-background/50 px-2 py-0.5 font-mono text-[11px] text-muted"
                      >
                        {name}
                      </span>
                    ))}
                  </div>
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
