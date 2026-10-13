import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi, type Mock } from 'vitest';
import { ApiError, api } from '@/lib/api';
import { LoginView } from '@/components/LoginView';

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
      login: vi.fn(),
      redeemInvite: vi.fn(),
      getSetupStatus: vi.fn(),
      setupAdmin: vi.fn(),
    },
  };
});

const login = api.login as unknown as Mock;
const redeemInvite = api.redeemInvite as unknown as Mock;
const getSetupStatus = api.getSetupStatus as unknown as Mock;
const setupAdmin = api.setupAdmin as unknown as Mock;

const SESSION = {
  token: 'c93_session',
  admin: false,
  tenant: 'acme',
  user: { id: 'usr_1', name: 'ada', operator: false, disabled: false, createdAt: 1 },
};

describe('LoginView', () => {
  beforeEach(() => {
    login.mockReset();
    redeemInvite.mockReset();
    getSetupStatus.mockReset();
    setupAdmin.mockReset();
    // By default the cluster already has an operator, so the card stays a login.
    getSetupStatus.mockResolvedValue({ needsSetup: false });
  });

  it('shows the username and password fields by default', () => {
    render(<LoginView onSubmit={vi.fn()} />);

    expect(screen.getByLabelText('Username')).toBeInTheDocument();
    expect(screen.getByLabelText('Password')).toBeInTheDocument();
    expect(screen.getByLabelText('Tenant (optional)')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeInTheDocument();
  });

  it('exchanges credentials for a token and hands it to onSubmit', async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn();
    login.mockResolvedValue(SESSION);

    render(<LoginView onSubmit={onSubmit} />);
    await user.type(screen.getByLabelText('Username'), 'ada');
    await user.type(screen.getByLabelText('Password'), 's3cret-pass');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    await waitFor(() => expect(login).toHaveBeenCalledWith({ username: 'ada', password: 's3cret-pass' }));
    await waitFor(() => expect(onSubmit).toHaveBeenCalledWith('c93_session'));
  });

  it('includes the optional tenant when one is given', async () => {
    const user = userEvent.setup();
    login.mockResolvedValue(SESSION);

    render(<LoginView onSubmit={vi.fn()} />);
    await user.type(screen.getByLabelText('Username'), 'ada');
    await user.type(screen.getByLabelText('Password'), 's3cret-pass');
    await user.type(screen.getByLabelText('Tenant (optional)'), 'globex');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    await waitFor(() =>
      expect(login).toHaveBeenCalledWith({ username: 'ada', password: 's3cret-pass', tenant: 'globex' }),
    );
  });

  it('renders the API error when sign-in fails', async () => {
    const user = userEvent.setup();
    login.mockRejectedValue(new ApiError(401, 'Invalid username or password'));

    render(<LoginView onSubmit={vi.fn()} />);
    await user.type(screen.getByLabelText('Username'), 'ada');
    await user.type(screen.getByLabelText('Password'), 'wrong');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid username or password');
  });

  it('reveals the bearer token field when switching to token mode', async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn();

    render(<LoginView onSubmit={onSubmit} />);
    await user.click(screen.getByRole('button', { name: /use api token instead/i }));

    const tokenInput = screen.getByLabelText('Bearer token');
    expect(tokenInput).toBeInTheDocument();
    expect(screen.queryByLabelText('Username')).not.toBeInTheDocument();

    await user.type(tokenInput, 'c93_manual');
    await user.click(screen.getByRole('button', { name: 'Connect' }));

    await waitFor(() => expect(onSubmit).toHaveBeenCalledWith('c93_manual'));
    expect(login).not.toHaveBeenCalled();
  });

  it('renders the redeem invite form', async () => {
    const user = userEvent.setup();

    render(<LoginView onSubmit={vi.fn()} />);
    await user.click(screen.getByRole('button', { name: /redeem an invite/i }));

    expect(screen.getByLabelText('Invite Code')).toBeInTheDocument();
    expect(screen.getByLabelText('Username')).toBeInTheDocument();
    expect(screen.getByLabelText('Password')).toBeInTheDocument();
    expect(screen.getByLabelText('Confirm Password')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Redeem invite' })).toBeInTheDocument();
  });

  it('rejects mismatched passwords on the redeem form', async () => {
    const user = userEvent.setup();

    render(<LoginView onSubmit={vi.fn()} />);
    await user.click(screen.getByRole('button', { name: /redeem an invite/i }));
    await user.type(screen.getByLabelText('Invite Code'), 'inv_code');
    await user.type(screen.getByLabelText('Username'), 'ada');
    await user.type(screen.getByLabelText('Password'), 's3cret');
    await user.type(screen.getByLabelText('Confirm Password'), 'different');
    await user.click(screen.getByRole('button', { name: 'Redeem invite' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Passwords do not match');
    expect(redeemInvite).not.toHaveBeenCalled();
  });

  it('redeems an invite and hands the session token to onSubmit', async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn();
    redeemInvite.mockResolvedValue(SESSION);

    render(<LoginView onSubmit={onSubmit} />);
    await user.click(screen.getByRole('button', { name: /redeem an invite/i }));
    await user.type(screen.getByLabelText('Invite Code'), 'inv_code');
    await user.type(screen.getByLabelText('Username'), 'ada');
    await user.type(screen.getByLabelText('Password'), 's3cret');
    await user.type(screen.getByLabelText('Confirm Password'), 's3cret');
    await user.click(screen.getByRole('button', { name: 'Redeem invite' }));

    await waitFor(() =>
      expect(redeemInvite).toHaveBeenCalledWith({ code: 'inv_code', name: 'ada', password: 's3cret' }),
    );
    await waitFor(() => expect(onSubmit).toHaveBeenCalledWith('c93_session'));
  });

  it('offers initial setup when the cluster has no operator account', async () => {
    getSetupStatus.mockResolvedValue({ needsSetup: true });

    render(<LoginView onSubmit={vi.fn()} />);

    expect(await screen.findByRole('heading', { name: 'Initial Setup' })).toBeInTheDocument();
    expect(screen.getByText(/create your administrator account/i)).toBeInTheDocument();
    expect(screen.getByLabelText('Username')).toHaveValue('admin');
    expect(screen.getByLabelText('Confirm Password')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Create Administrator Account' })).toBeInTheDocument();
  });

  it('creates the administrator account and hands the token to onSubmit', async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn();
    getSetupStatus.mockResolvedValue({ needsSetup: true });
    setupAdmin.mockResolvedValue({ token: 'c93_admin', user: SESSION.user });

    render(<LoginView onSubmit={onSubmit} />);
    await screen.findByLabelText('Confirm Password');
    await user.type(screen.getByLabelText('Password'), 's3cret');
    await user.type(screen.getByLabelText('Confirm Password'), 's3cret');
    await user.click(screen.getByRole('button', { name: 'Create Administrator Account' }));

    await waitFor(() => expect(setupAdmin).toHaveBeenCalledWith({ username: 'admin', password: 's3cret' }));
    await waitFor(() => expect(onSubmit).toHaveBeenCalledWith('c93_admin'));
  });

  it('rejects mismatched passwords on the setup form', async () => {
    const user = userEvent.setup();
    getSetupStatus.mockResolvedValue({ needsSetup: true });

    render(<LoginView onSubmit={vi.fn()} />);
    await screen.findByLabelText('Confirm Password');
    await user.type(screen.getByLabelText('Password'), 's3cret');
    await user.type(screen.getByLabelText('Confirm Password'), 'different');
    await user.click(screen.getByRole('button', { name: 'Create Administrator Account' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Passwords do not match');
    expect(setupAdmin).not.toHaveBeenCalled();
  });
});
