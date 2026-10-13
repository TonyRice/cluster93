'use client';

import { Fragment, useCallback, useEffect, useMemo, useState } from 'react';
import {
  Check,
  ChevronRight,
  Copy,
  FileText,
  Folder,
  FolderOpen,
  Home,
  Loader2,
  RefreshCw,
  Search,
  TriangleAlert,
  X,
} from 'lucide-react';
import { api, ApiError } from '@/lib/api';
import type { FileEntry } from '@/lib/types';
import { cn, formatBytes, formatDateTime } from '@/lib/utils';

interface FilesTabProps {
  refreshKey: number;
}

/** The leaf name of a VFS path (after the last `/`). */
function fileName(path: string): string {
  const idx = path.lastIndexOf('/');
  return idx === -1 ? path : path.slice(idx + 1);
}

/** The lowercase file extension, or `''` when there is none. */
function extOf(path: string): string {
  const base = fileName(path);
  const dot = base.lastIndexOf('.');
  return dot === -1 ? '' : base.slice(dot + 1).toLowerCase();
}

/** Extensions treated as text when the server reports a generic content type. */
const TEXTUAL_EXTS = new Set([
  'txt', 'md', 'markdown', 'json', 'js', 'mjs', 'cjs', 'jsx', 'ts', 'tsx',
  'html', 'htm', 'xml', 'yaml', 'yml', 'toml', 'css', 'csv', 'tsv', 'log',
  'sh', 'bash', 'py', 'rb', 'java', 'go', 'rs', 'c', 'h', 'cpp', 'ini',
  'conf', 'env', 'sql', 'graphql', 'proto', 'gitignore', 'dockerfile', 'properties',
]);

/** Whether a file can be previewed as text/code in the modal. */
function isTextual(file: FileEntry): boolean {
  const type = (file.contentType || '').toLowerCase();
  if (!type || type === 'application/octet-stream') {
    return TEXTUAL_EXTS.has(extOf(file.path));
  }
  if (type.startsWith('text/')) return true;
  if (type.includes('json') || type.includes('javascript') || type.includes('ecmascript')) return true;
  if (type.includes('html') || type.includes('xml')) return true;
  if (type.includes('markdown')) return true;
  return false;
}

/** Splits a directory's immediate children into subdirectories and direct files. */
function childPaths(files: FileEntry[], currentDir: string) {
  const prefix = currentDir ? `${currentDir}/` : '';
  const dirs = new Set<string>();
  const direct: FileEntry[] = [];
  for (const file of files) {
    if (!file.path.startsWith(prefix)) continue;
    const rest = file.path.slice(prefix.length);
    if (rest.length === 0) continue;
    const slash = rest.indexOf('/');
    if (slash === -1) direct.push(file);
    else dirs.add(rest.slice(0, slash));
  }
  return { dirs: Array.from(dirs).sort(), direct };
}

/** A hierarchical VFS browser with breadcrumbs, search, and a text preview modal. */
export function FilesTab({ refreshKey }: FilesTabProps) {
  const [files, setFiles] = useState<FileEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [query, setQuery] = useState('');
  const [currentDir, setCurrentDir] = useState('');
  const [preview, setPreview] = useState<FileEntry | null>(null);

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

  const searching = query.trim().length > 0;
  const needle = query.trim().toLowerCase();

  const searchResults = useMemo(() => {
    if (!searching) return [];
    return files.filter(
      (file) =>
        file.path.toLowerCase().includes(needle) ||
        (file.contentType || '').toLowerCase().includes(needle),
    );
  }, [files, needle, searching]);

  const { dirs, direct } = useMemo(() => childPaths(files, currentDir), [files, currentDir]);
  const segments = currentDir ? currentDir.split('/') : [];

  function navigate(dir: string) {
    setCurrentDir(dir);
    setQuery('');
  }

  const totalBytes = useMemo(
    () => files.reduce((sum, file) => sum + (file.size || 0), 0),
    [files],
  );

  const header = (
    <thead className="text-xs uppercase tracking-wider text-subtle">
      <tr className="border-b border-border/70">
        <th className="px-5 py-2.5 font-medium">Name</th>
        <th className="px-5 py-2.5 font-medium">Size</th>
        <th className="px-5 py-2.5 font-medium">Modified</th>
        <th className="px-5 py-2.5 font-medium">Type</th>
      </tr>
    </thead>
  );

  return (
    <>
      <section className="panel">
        <div className="panel-header">
          <div>
            <h2 className="text-sm font-semibold tracking-tight">Files</h2>
            <p className="text-xs text-subtle">
              {files.length} file{files.length === 1 ? '' : 's'} · {formatBytes(totalBytes)}
            </p>
          </div>
          <div className="flex items-center gap-2">
            <div className="relative">
              <Search
                className="pointer-events-none absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-subtle"
                aria-hidden
              />
              <input
                type="search"
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                placeholder="Search the VFS…"
                className="input w-56 pl-8"
              />
            </div>
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

        <div className="flex flex-wrap items-center gap-1 border-b border-border/70 px-5 py-3">
          <Breadcrumbs segments={segments} onNavigate={navigate} />
        </div>

        {loading ? (
          <Loading label="Loading files…" />
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
        ) : searching ? (
          searchResults.length === 0 ? (
            <Empty label="No files match your search." />
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm">
                {header}
                <tbody>
                  {searchResults.map((file) => {
                    const previewable = isTextual(file);
                    return (
                      <tr
                        key={file.path}
                        className="border-b border-border/40 last:border-0 hover:bg-elevated/40"
                      >
                        <td className="px-5 py-3">
                          {previewable ? (
                            <button
                              type="button"
                              onClick={() => setPreview(file)}
                              className="group flex items-center gap-2 text-left"
                              title={`Preview ${file.path}`}
                            >
                              <FileText
                                className="h-4 w-4 shrink-0 text-subtle group-hover:text-accent"
                                aria-hidden
                              />
                              <span className="font-mono text-xs text-foreground group-hover:text-accent group-hover:underline">
                                {fileName(file.path)}
                              </span>
                            </button>
                          ) : (
                            <span className="flex items-center gap-2">
                              <FileText className="h-4 w-4 shrink-0 text-subtle" aria-hidden />
                              <span className="font-mono text-xs text-foreground">
                                {fileName(file.path)}
                              </span>
                            </span>
                          )}
                          <div className="mt-0.5 pl-6 font-mono text-[11px] text-subtle">
                            {file.path}
                          </div>
                        </td>
                        <td className="px-5 py-3 tabular-nums text-muted">
                          {formatBytes(file.size)}
                        </td>
                        <td
                          className="px-5 py-3 text-xs text-muted"
                          title={formatDateTime(file.createdAt)}
                        >
                          {formatDateTime(file.createdAt)}
                        </td>
                        <td className="px-5 py-3">
                          <TypeBadge file={file} />
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )
        ) : dirs.length === 0 && direct.length === 0 ? (
          <Empty label={currentDir ? 'This directory is empty.' : 'No files in the tenant VFS.'} />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              {header}
              <tbody>
                {dirs.map((dir) => (
                  <tr
                    key={`dir:${dir}`}
                    className="border-b border-border/40 hover:bg-elevated/40"
                  >
                    <td className="px-5 py-3" colSpan={4}>
                      <button
                        type="button"
                        onClick={() => navigate(currentDir ? `${currentDir}/${dir}` : dir)}
                        className="flex w-full items-center gap-2 text-left"
                        title={`Open ${dir}`}
                      >
                        <Folder className="h-4 w-4 shrink-0 text-accent" aria-hidden />
                        <span className="font-medium text-foreground">{dir}/</span>
                        <span className="text-xs text-subtle">directory</span>
                        <ChevronRight
                          className="ml-auto h-4 w-4 text-subtle"
                          aria-hidden
                        />
                      </button>
                    </td>
                  </tr>
                ))}
                {direct.map((file) => {
                  const previewable = isTextual(file);
                  return (
                    <tr
                      key={file.path}
                      className="border-b border-border/40 last:border-0 hover:bg-elevated/40"
                    >
                      <td className="px-5 py-3">
                        {previewable ? (
                          <button
                            type="button"
                            onClick={() => setPreview(file)}
                            className="group flex items-center gap-2 text-left"
                            title={`Preview ${file.path}`}
                          >
                            <FileText
                              className="h-4 w-4 shrink-0 text-subtle group-hover:text-accent"
                              aria-hidden
                            />
                            <span className="font-mono text-xs text-foreground group-hover:text-accent group-hover:underline">
                              {fileName(file.path)}
                            </span>
                          </button>
                        ) : (
                          <span className="flex items-center gap-2">
                            <FileText className="h-4 w-4 shrink-0 text-subtle" aria-hidden />
                            <span className="font-mono text-xs text-foreground">
                              {fileName(file.path)}
                            </span>
                          </span>
                        )}
                      </td>
                      <td className="px-5 py-3 tabular-nums text-muted">
                        {formatBytes(file.size)}
                      </td>
                      <td
                        className="px-5 py-3 text-xs text-muted"
                        title={formatDateTime(file.createdAt)}
                      >
                        {formatDateTime(file.createdAt)}
                      </td>
                      <td className="px-5 py-3">
                        <TypeBadge file={file} />
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {preview ? <FilePreviewModal file={preview} onClose={() => setPreview(null)} /> : null}
    </>
  );
}

function Breadcrumbs({
  segments,
  onNavigate,
}: {
  segments: string[];
  onNavigate: (dir: string) => void;
}) {
  return (
    <nav className="flex flex-wrap items-center gap-1 text-sm" aria-label="Directory breadcrumb">
      <button
        type="button"
        onClick={() => onNavigate('')}
        aria-current={segments.length === 0 ? 'page' : undefined}
        className={cn(
          'flex items-center gap-1 rounded-md px-2 py-1 transition-colors',
          segments.length === 0
            ? 'text-foreground'
            : 'text-muted hover:bg-elevated/60 hover:text-foreground',
        )}
      >
        <Home className="h-3.5 w-3.5" aria-hidden />
        root
      </button>
      {segments.map((segment, index) => {
        const path = segments.slice(0, index + 1).join('/');
        const isLast = index === segments.length - 1;
        return (
          <Fragment key={path}>
            <ChevronRight className="h-3.5 w-3.5 text-subtle" aria-hidden />
            <button
              type="button"
              onClick={() => onNavigate(path)}
              aria-current={isLast ? 'page' : undefined}
              className={cn(
                'rounded-md px-2 py-1 font-mono text-xs transition-colors',
                isLast
                  ? 'text-foreground'
                  : 'text-muted hover:bg-elevated/60 hover:text-foreground',
              )}
            >
              {segment}
            </button>
          </Fragment>
        );
      })}
    </nav>
  );
}

function TypeBadge({ file }: { file: FileEntry }) {
  return (
    <span className="rounded-md border border-border/70 bg-elevated/60 px-2 py-0.5 font-mono text-[11px] text-muted">
      {file.contentType || 'application/octet-stream'}
    </span>
  );
}

function Loading({ label }: { label: string }) {
  return (
    <div className="flex items-center justify-center gap-2 p-12 text-sm text-muted">
      <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
      {label}
    </div>
  );
}

function Empty({ label }: { label: string }) {
  return (
    <div className="flex flex-col items-center justify-center gap-2 p-12 text-center text-sm text-subtle">
      <FolderOpen className="h-6 w-6" aria-hidden />
      {label}
    </div>
  );
}

/** Modal that loads and shows a text/code file, with copy and error handling. */
function FilePreviewModal({ file, onClose }: { file: FileEntry; onClose: () => void }) {
  const [content, setContent] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    setContent(null);
    api
      .getFileContent(file.path, controller.signal)
      .then((text) => setContent(text))
      .catch((cause) => {
        if (cause instanceof DOMException && cause.name === 'AbortError') return;
        setError(cause instanceof ApiError ? cause.message : 'Failed to load file content.');
      })
      .finally(() => setLoading(false));
    return () => controller.abort();
  }, [file.path]);

  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') onClose();
    }
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [onClose]);

  async function copy() {
    if (content === null) return;
    try {
      await navigator.clipboard.writeText(content);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1500);
    } catch {
      /* clipboard may be unavailable (insecure context) */
    }
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center p-6"
      role="dialog"
      aria-modal="true"
      aria-label={`Preview ${file.path}`}
    >
      <div
        className="absolute inset-0 bg-background/70 backdrop-blur-sm"
        onClick={onClose}
        aria-hidden
      />
      <div className="relative flex max-h-[85vh] w-full max-w-3xl flex-col overflow-hidden rounded-xl border border-border/80 bg-surface shadow-panel">
        <div className="panel-header">
          <div className="min-w-0 flex-1">
            <h2 className="truncate font-mono text-sm text-foreground" title={file.path}>
              {file.path}
            </h2>
            <p className="mt-0.5 text-xs text-subtle">
              {formatBytes(file.size)} · {file.contentType || 'application/octet-stream'}
            </p>
          </div>
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => void copy()}
              disabled={content === null}
              className="btn btn-ghost"
              title="Copy contents"
            >
              {copied ? (
                <Check className="h-4 w-4 text-positive" aria-hidden />
              ) : (
                <Copy className="h-4 w-4" aria-hidden />
              )}
              {copied ? 'Copied' : 'Copy'}
            </button>
            <button
              type="button"
              onClick={onClose}
              className="btn btn-ghost"
              aria-label="Close preview"
            >
              <X className="h-4 w-4" aria-hidden />
            </button>
          </div>
        </div>

        <div className="min-h-0 flex-1 overflow-auto">
          {loading ? (
            <div className="flex items-center justify-center gap-2 p-12 text-sm text-muted">
              <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
              Loading content…
            </div>
          ) : error ? (
            <div className="p-6">
              <div className="flex items-start gap-3 rounded-lg border border-danger/40 bg-danger/10 p-4 text-sm text-danger">
                <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
                <span className="flex-1">{error}</span>
              </div>
            </div>
          ) : (
            <pre className="whitespace-pre-wrap break-words p-5 font-mono text-xs leading-relaxed text-foreground">
              {content}
            </pre>
          )}
        </div>
      </div>
    </div>
  );
}
