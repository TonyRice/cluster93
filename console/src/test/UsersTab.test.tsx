import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi, type Mock } from 'vitest';
import { api } from '@/lib/api';
import { UsersTab } from '@/components/UsersTab';

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
      getUsers: vi.fn(),
      getAdminInvites: vi.fn(),
      createAdminInvite: vi.fn(),
      revokeAdminInvite: vi.fn(),
    },
  };
});

const getUsers = api.getUsers as unknown as Mock;
const getAdminInvites = api.getAdminInvites as unknown as Mock;
const createAdminInvite = api.createAdminInvite as unknown as Mock;
const revokeAdminInvite = api.revokeAdminInvite as unknown as Mock;

beforeEach(() => {
  getUsers.mockReset();
  getAdminInvites.mockReset();
  createAdminInvite.mockReset();
  revokeAdminInvite.mockReset();

  getUsers.mockResolvedValue({
    users: [
      { id: 'usr_1', name: 'ada', operator: true, disabled: false, createdAt: 1, hasPassword: true },
      { id: 'usr_2', name: 'bob', operator: false, disabled: false, createdAt: 2, hasPassword: false },
    ],
  });
  getAdminInvites.mockResolvedValue({ invites: [] });
});

describe('UsersTab', () => {
  it('renders the cluster users and their password state', async () => {
    render(<UsersTab refreshKey={0} />);

    expect(await screen.findByText('ada')).toBeInTheDocument();
    expect(screen.getByText('bob')).toBeInTheDocument();
    expect(screen.getByText('password set')).toBeInTheDocument();
    expect(screen.getByText('no password')).toBeInTheDocument();
  });

  it('creates an operator invite and shows the code once', async () => {
    const user = userEvent.setup();
    createAdminInvite.mockResolvedValue({
      id: 'inv_1',
      tenant: '@operators',
      roles: [],
      groups: [],
      user: 'carol',
      code: 'invite-code-1',
    });

    render(<UsersTab refreshKey={0} />);
    await screen.findByText('ada');

    await user.click(screen.getByRole('button', { name: /invite operator/i }));
    await user.type(screen.getByLabelText('Username (optional)'), 'carol');
    await user.click(screen.getByRole('button', { name: 'Create' }));

    await waitFor(() => expect(createAdminInvite).toHaveBeenCalledWith({ user: 'carol' }));
    expect(await screen.findByText('invite-code-1')).toBeInTheDocument();
  });
});
