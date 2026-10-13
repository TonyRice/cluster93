import { render, screen, fireEvent } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi, type Mock } from 'vitest';
import { AppsTab, buildAppUrl, defaultIngressHost } from '@/components/AppsTab';
import { api } from '@/lib/api';

vi.mock('@/lib/api', () => ({
  api: {
    getApps: vi.fn(),
    getDeployments: vi.fn(),
    promoteApp: vi.fn(),
    rollbackApp: vi.fn(),
  },
  ApiError: class ApiError extends Error {
    readonly status: number;
    constructor(status: number, message: string) {
      super(message);
      this.name = 'ApiError';
      this.status = status;
    }
  },
}));

const getApps = api.getApps as unknown as Mock;
const getDeployments = api.getDeployments as unknown as Mock;

describe('defaultIngressHost & buildAppUrl', () => {
  it('derives default ingress host and preserves port', () => {
    // In vitest jsdom environment, window.location.hostname is localhost
    // window.location.port is empty by default unless mocked
    expect(buildAppUrl('my-subdomain', 'localhost:8000')).toBe(
      'http://my-subdomain.localhost:8000/',
    );
  });

  it('strips leading console or deployment subdomains', () => {
    const originalLocation = window.location;
    // Mock location with console subdomain and custom port
    // @ts-expect-error override location for test
    delete window.location;
    window.location = {
      ...originalLocation,
      hostname: 'console.cluster93.internal',
      host: 'console.cluster93.internal:8000',
      port: '8000',
      protocol: 'http:',
    } as unknown as Location;

    expect(defaultIngressHost()).toBe('cluster93.internal:8000');

    window.location = originalLocation;
  });
});

describe('AppsTab ingress host configuration', () => {
  beforeEach(() => {
    localStorage.clear();
    getApps.mockReset();
    getDeployments.mockReset();
    getApps.mockResolvedValue({
      apps: [
        {
          name: 'demo-app',
          tenant: 'default',
          activeDeploymentId: 'dpl_123',
          updatedAt: Date.now(),
          deploymentHistory: ['dpl_123'],
        },
      ],
    });
    getDeployments.mockResolvedValue({
      deployments: [
        {
          deploymentId: 'dpl_123',
          appName: 'demo-app',
          tenant: 'default',
          createdAt: Date.now(),
          vfsPrefix: 'apps/default/demo-app/dpl_123',
        },
      ],
    });
  });

  it('renders configured ingress host and updates preview/canonical URLs', async () => {
    localStorage.setItem('c93_ingress_host', 'localhost:8000');

    render(<AppsTab refreshKey={0} />);

    const matching = await screen.findAllByText('demo-app');
    expect(matching.length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText('localhost:8000')).toBeInTheDocument();

    const openLink = screen.getByTitle('Open canonical URL');
    expect(openLink).toHaveAttribute('href', 'http://demo-app--default.localhost:8000/');

    const previewLink = await screen.findByTitle('Open preview deployment');
    expect(previewLink).toHaveAttribute(
      'href',
      'http://dpl_123--demo-app--default.localhost:8000/',
    );
  });

  it('allows user to change ingress host through the UI', async () => {
    render(<AppsTab refreshKey={0} />);

    const matching = await screen.findAllByText('demo-app');
    expect(matching.length).toBeGreaterThanOrEqual(1);

    const configureBtn = screen.getByText('Configure');
    fireEvent.click(configureBtn);

    const input = screen.getByPlaceholderText('e.g. localhost:8000');
    fireEvent.change(input, { target: { value: 'mycluster.local:9000' } });

    const saveBtn = screen.getByText('Save');
    fireEvent.click(saveBtn);

    expect(screen.getByText('mycluster.local:9000')).toBeInTheDocument();
    expect(localStorage.getItem('c93_ingress_host')).toBe('mycluster.local:9000');

    const openLink = screen.getByTitle('Open canonical URL');
    expect(openLink).toHaveAttribute('href', 'http://demo-app--default.mycluster.local:9000/');
  });
});
