'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { FileText, FolderOpen, Loader2, RefreshCw, TriangleAlert } from 'lucide-react';
import { api, ApiError } from '@/lib/api';
import type { FileEntry } from '@/lib/types';
import { cn, formatBytes, formatDateTime } from '@/lib/utils';

interface FilesTabProps {
  refreshKey: number;
}

/** Lists the tenant's virtual file system entries with size and type. */
export function FilesTab({ refreshKey }: FilesTabProps) {
  const [files, setFiles] = useState<FileEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [query, setQuery] = useState('');

  const load = useCallback(async (signal?: AbortSignal) => {
    setLoading(true);
    try {
      const { files: list } = await api.getFiles(signal);
      setFiles(list);
      setError(null);
    } catch (cause) {
      if (cause instanceof DOMException && cause.name === 'AbortError') return;
      setError(cause instanceof ApiError ? cause.message : 'Failed to load files.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    void load(controller.signal);
    return () => controller.abort();
  }, [load, refreshKey]);

  const visible = useMemo(() => {
    const needle = query.trim().toLowerCase();
    if (!needle) return files;
    return files.filter(
      (file) =>
        file.path.toLowerCase().includes(needle) ||
        file.contentType.toLowerCase().includes(needle),
    );
  }, [files, query]);

  const totalBytes = useMemo(
    () => files.reduce((sum, file) => sum + (file.size || 0), 0),
    [files],
  );

  return (
    <section className="panel">
      <div className="panel-header">
        <div>
          <h2 className="text-sm font-semibold tracking-tight">Files</h2>
          <p className="text-xs text-subtle">
            {files.length} file{files.length === 1 ? '' : 's'} · {formatBytes(totalBytes)}
          </p>
        </div>
        <div className="flex items-center gap-2">
          <input
            type="search"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder="Filter by path or type…"
            className="input w-56"
          />
          <button
            type="button"
            onClick={() => void load()}
            className="btn btn-ghost"
            aria-label="Reload files"
          >
            <RefreshCw className={cn('h-4 w-4', loading && 'animate-spin')} aria-hidden />
          </button>
        </div>
      </div>

      {loading ? (
        <div className="flex items-center justify-center gap-2 p-12 text-sm text-muted">
          <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
          Loading files…
        </div>
      ) : error ? (
        <div className="p-6">
          <div className="flex items-start gap-3 rounded-lg border border-danger/40 bg-danger/10 p-4 text-sm text-danger">
            <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
            <span className="flex-1">{error}</span>
            <button type="button" onClick={() => void load()} className="btn btn-ghost">
              Retry
            </button>
          </div>
        </div>
      ) : visible.length === 0 ? (
        <div className="flex flex-col items-center justify-center gap-2 p-12 text-center text-sm text-subtle">
          <FolderOpen className="h-6 w-6" aria-hidden />
          {files.length === 0 ? 'No files in the tenant VFS.' : 'No files match your filter.'}
        </div>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-left text-sm">
            <thead className="text-xs uppercase tracking-wider text-subtle">
              <tr className="border-b border-border/70">
                <th className="px-5 py-2.5 font-medium">Path</th>
                <th className="px-5 py-2.5 font-medium">Size</th>
                <th className="px-5 py-2.5 font-medium">Modified</th>
                <th className="px-5 py-2.5 font-medium">Type</th>
              </tr>
            </thead>
            <tbody>
              {visible.map((file) => (
                <tr
                  key={file.path}
                  className="border-b border-border/40 last:border-0 hover:bg-elevated/40"
                >
                  <td className="px-5 py-3">
                    <span className="inline-flex items-center gap-2">
                      <FileText className="h-4 w-4 shrink-0 text-subtle" aria-hidden />
                      <span className="font-mono text-xs text-foreground">{file.path}</span>
                    </span>
                  </td>
                  <td className="px-5 py-3 tabular-nums text-muted">{formatBytes(file.size)}</td>
                  <td
                    className="px-5 py-3 text-xs text-muted"
                    title={formatDateTime(file.createdAt)}
                  >
                    {formatDateTime(file.createdAt)}
                  </td>
                  <td className="px-5 py-3">
                    <span className="rounded-md border border-border/70 bg-elevated/60 px-2 py-0.5 font-mono text-[11px] text-muted">
                      {file.contentType || 'application/octet-stream'}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
