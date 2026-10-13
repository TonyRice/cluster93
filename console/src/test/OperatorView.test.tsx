import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi, type Mock } from 'vitest';
import { OperatorView } from '@/components/OperatorView';
import { api } from '@/lib/api';

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
      getNodes: vi.fn(),
      getTenants: vi.fn(),
    },
  };
});

const getNodes = api.getNodes as unknown as Mock;
const getTenants = api.getTenants as unknown as Mock;

function renderOverview() {
  return render(
    <OperatorView
      whoami={{ admin: true }}
      activeTab="overview"
      refreshKey={0}
      health={{ status: 'UP', node: 'node-a' }}
    />,
  );
}

beforeEach(() => {
  getNodes.mockReset();
  getTenants.mockReset();
  getNodes.mockResolvedValue({
    nodes: [
      { id: 'node-a', capacity: 8, running: 2, lastSeen: Date.now() },
      { id: 'node-b', capacity: 4, running: 4, lastSeen: Date.now() },
    ],
  });
  getTenants.mockResolvedValue({ tenants: [] });
});

describe('OperatorView overview', () => {
  it('renders the four standard overview stat cards', async () => {
    renderOverview();

    expect(await screen.findByText('Compute Nodes')).toBeInTheDocument();
    expect(screen.getByText('Worker Concurrency')).toBeInTheDocument();
    expect(screen.getByText('Active Executions')).toBeInTheDocument();
    expect(screen.getByText('Available Slots')).toBeInTheDocument();
  });

  it('summarises cluster capacity in the stat hints', async () => {
    renderOverview();

    expect(await screen.findByText('Compute Nodes')).toBeInTheDocument();

    // capacity 8 + 4 = 12, running 2 + 4 = 6 -> 6 / 12 = 50% utilisation.
    expect(screen.getByText('50% cluster utilization')).toBeInTheDocument();
  });

  it('does not render the retired headroom labels', async () => {
    renderOverview();

    expect(await screen.findByText('Compute Nodes')).toBeInTheDocument();
    expect(screen.queryByText('Available Headroom')).not.toBeInTheDocument();
    expect(screen.queryByText('idle slots ready for work')).not.toBeInTheDocument();
  });
});
