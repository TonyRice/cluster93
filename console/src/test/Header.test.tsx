import type { ComponentProps } from 'react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { Header } from '@/components/Header';
import type { Tenant } from '@/lib/types';

const TENANTS: Tenant[] = [
  { name: 'alpha', createdAt: 1_700_000_000_000 },
  { name: 'beta', createdAt: 1_700_000_000_000 },
];

type HeaderProps = ComponentProps<typeof Header>;

function renderHeader(overrides: Partial<HeaderProps> = {}) {
  const onLogout = vi.fn();
  const onSelectTenant = vi.fn();
  const view = render(
    <Header
      status="connected"
      role="operator"
      identity="operator@cluster"
      node="node-a"
      onLogout={onLogout}
      selectedTenant={null}
      tenants={TENANTS}
      onSelectTenant={onSelectTenant}
      {...overrides}
    />,
  );
  return { onLogout, onSelectTenant, ...view };
}

describe('Header', () => {
  it('renders the operator role badge and live connection status', () => {
    renderHeader();

    expect(screen.getByText('Operator')).toBeInTheDocument();
    expect(screen.getByText('Connected')).toBeInTheDocument();
    expect(screen.getByText('node: node-a')).toBeInTheDocument();
  });

  it('renders the user identity when scoped to a tenant', () => {
    renderHeader({ role: 'tenant', identity: 'ada', tenants: [], onSelectTenant: undefined });

    expect(screen.getByText('ada')).toBeInTheDocument();
    // A tenant user never sees the operator role badge or the context switcher.
    expect(screen.queryByText('Operator')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Cluster' })).not.toBeInTheDocument();
  });

  it("shows the operator's tenant dropdown selector", () => {
    renderHeader();

    const switcher = screen.getByRole('button', { name: 'Cluster' });
    expect(switcher).toHaveAttribute('aria-haspopup', 'menu');
    expect(switcher).toHaveAttribute('aria-expanded', 'false');
  });

  it('calls onSelectTenant when a tenant workspace is selected', async () => {
    const user = userEvent.setup();
    const { onSelectTenant } = renderHeader();

    await user.click(screen.getByRole('button', { name: 'Cluster' }));
    expect(screen.getByRole('menu')).toBeInTheDocument();

    await user.click(screen.getByRole('menuitem', { name: 'alpha' }));

    expect(onSelectTenant).toHaveBeenCalledTimes(1);
    expect(onSelectTenant).toHaveBeenCalledWith('alpha');
  });

  it('returns to the cluster context from the switcher', async () => {
    const user = userEvent.setup();
    const { onSelectTenant } = renderHeader({ selectedTenant: 'alpha' });

    await user.click(screen.getByRole('button', { name: 'Tenant: alpha' }));
    await user.click(screen.getByRole('menuitem', { name: 'Cluster Infrastructure' }));

    expect(onSelectTenant).toHaveBeenCalledWith(null);
  });

  it('invokes onLogout when signing out', async () => {
    const user = userEvent.setup();
    const { onLogout } = renderHeader();

    await user.click(screen.getByRole('button', { name: /sign out/i }));

    expect(onLogout).toHaveBeenCalledTimes(1);
  });
});
