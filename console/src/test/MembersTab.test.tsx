import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi, type Mock } from 'vitest';
import { api } from '@/lib/api';
import { MembersTab } from '@/components/MembersTab';

vi.mock('@/lib/api', () => {
  class ApiError extends Error {
    readonly status: number;
    constructor(status: number, message: string) {
      super(message);
      this.name = 'ApiError';
      this.status = status;
    }
  }
  return {
    ApiError,
    api: {
      getMembers: vi.fn(),
      getTenantInvites: vi.fn(),
      createTenantInvite: vi.fn(),
      revokeTenantInvite: vi.fn(),
    },
  };
});

const getMembers = api.getMembers as unknown as Mock;
const getTenantInvites = api.getTenantInvites as unknown as Mock;
const createTenantInvite = api.createTenantInvite as unknown as Mock;
const revokeTenantInvite = api.revokeTenantInvite as unknown as Mock;

beforeEach(() => {
  getMembers.mockReset();
  getTenantInvites.mockReset();
  createTenantInvite.mockReset();
  revokeTenantInvite.mockReset();

  getMembers.mockResolvedValue({
    members: [
      {
        tenant: 'acme',
        user: 'ada',
        userId: 'usr_1',
        roles: ['owner'],
        groups: [],
        scopes: ['*'],
        owner: true,
        joinedAt: 1,
      },
      {
        tenant: 'acme',
        user: 'bob',
        userId: 'usr_2',
        roles: ['developer'],
        groups: ['web'],
        scopes: ['jobs:read'],
        owner: false,
        joinedAt: 2,
      },
    ],
  });
  getTenantInvites.mockResolvedValue({ invites: [] });
});

describe('MembersTab', () => {
  it('renders the tenant members with their roles', async () => {
    render(<MembersTab refreshKey={0} />);

    expect(await screen.findByText('ada')).toBeInTheDocument();
    expect(screen.getByText('bob')).toBeInTheDocument();
    expect(screen.getByText('owner')).toBeInTheDocument();
    expect(screen.getByText('developer')).toBeInTheDocument();
    expect(screen.getByText('web')).toBeInTheDocument();
  });

  it('invites a new member with the selected role', async () => {
    const user = userEvent.setup();
    createTenantInvite.mockResolvedValue({
      id: 'inv_1',
      tenant: 'acme',
      roles: ['viewer'],
      groups: [],
      user: 'carol',
      code: 'invite-code-1',
    });

    render(<MembersTab refreshKey={0} />);
    await screen.findByText('ada');

    await user.click(screen.getByRole('button', { name: /invite member/i }));
    await user.selectOptions(screen.getByLabelText('Role'), 'viewer');
    await user.type(screen.getByLabelText('Username (optional)'), 'carol');
    await user.click(screen.getByRole('button', { name: 'Create' }));

    await waitFor(() =>
      expect(createTenantInvite).toHaveBeenCalledWith({ roles: ['viewer'], user: 'carol' }),
    );
    expect(await screen.findByText('invite-code-1')).toBeInTheDocument();
  });
});
