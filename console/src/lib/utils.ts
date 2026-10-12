import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';

/**
 * Merges conditional class names and resolves Tailwind conflicts.
 * The idiomatic `cn(...)` helper used across the console components.
 */
export function cn(...inputs: ClassValue[]): string {
  return twMerge(clsx(inputs));
}

/** Formats a byte count with binary units (`1.4 MiB`). */
export function formatBytes(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined || Number.isNaN(bytes)) return '—';
  if (bytes < 1024) return `${bytes} B`;
  const units = ['KiB', 'MiB', 'GiB', 'TiB', 'PiB'];
  let value = bytes / 1024;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${value >= 100 ? value.toFixed(0) : value.toFixed(1)} ${units[unit]}`;
}

/** Formats a millisecond duration as `1h 02m 03s` / `4.2s` / `120ms`. */
export function formatDuration(ms: number | null | undefined): string {
  if (ms === null || ms === undefined || Number.isNaN(ms)) return '—';
  if (ms < 1000) return `${Math.max(0, Math.round(ms))}ms`;
  const totalSeconds = ms / 1000;
  if (totalSeconds < 60) return `${totalSeconds.toFixed(1)}s`;
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = Math.floor(totalSeconds % 60);
  const pad = (n: number) => String(n).padStart(2, '0');
  return hours > 0 ? `${hours}h ${pad(minutes)}m ${pad(seconds)}s` : `${minutes}m ${pad(seconds)}s`;
}

/** Formats an epoch-millisecond timestamp as a compact local relative time. */
export function formatRelative(timestamp: number | null | undefined): string {
  if (!timestamp) return '—';
  const deltaSeconds = Math.round((Date.now() - timestamp) / 1000);
  const abs = Math.abs(deltaSeconds);
  if (abs < 5) return 'just now';
  if (abs < 60) return `${abs}s ago`;
  if (abs < 3600) return `${Math.round(abs / 60)}m ago`;
  if (abs < 86400) return `${Math.round(abs / 3600)}h ago`;
  if (abs < 2592000) return `${Math.round(abs / 86400)}d ago`;
  return new Date(timestamp).toLocaleDateString();
}

/** Formats an epoch-millisecond timestamp for a table cell. */
export function formatDateTime(timestamp: number | null | undefined): string {
  if (!timestamp) return '—';
  return new Date(timestamp).toLocaleString();
}

/** The elapsed wall-clock duration of a job, falling back to "now" while running. */
export function jobDuration(job: {
  createdAt: number;
  startedAt?: number | null;
  finishedAt?: number | null;
}): number | null {
  const start = job.startedAt ?? job.createdAt;
  const end = job.finishedAt ?? (job.startedAt || job.finishedAt ? Date.now() : null);
  if (end === null) return null;
  return Math.max(0, end - start);
}

const TONES = {
  positive: 'border-positive/40 bg-positive/10 text-positive',
  warning: 'border-warning/40 bg-warning/10 text-warning',
  danger: 'border-danger/40 bg-danger/10 text-danger',
  accent: 'border-accent/40 bg-accent/10 text-accent',
  neutral: 'border-border/80 bg-elevated text-muted',
} as const;

export type Tone = keyof typeof TONES;

/** Tailwind classes for a status pill in the given tone. */
export function toneClasses(tone: Tone): string {
  return TONES[tone];
}

/** Maps a job status to a semantic tone. */
export function jobStatusTone(status: string): Tone {
  switch (status) {
    case 'SUCCEEDED':
      return 'positive';
    case 'RUNNING':
      return 'accent';
    case 'QUEUED':
      return 'warning';
    case 'FAILED':
    case 'TIMED_OUT':
    case 'ORPHANED':
      return 'danger';
    default:
      return 'neutral';
  }
}

/** Maps a service status to a semantic tone. */
export function serviceStatusTone(status: string): Tone {
  const normalized = status.toUpperCase();
  if (['RUNNING', 'ACTIVE', 'HEALTHY', 'STARTED'].includes(normalized)) return 'positive';
  if (['STARTING', 'PENDING', 'RESTARTING', 'BACKING_OFF'].includes(normalized)) return 'warning';
  if (['FAILED', 'STOPPED', 'CRASHED', 'DISABLED'].includes(normalized)) return 'danger';
  return 'neutral';
}

