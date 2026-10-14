// fs: the file system (D-079), Node-shaped. The tenant's files are one tree from /, with the job's own /tmp in it.
// Every call comes as a promise (fs.promises, and callback-style fs.x(…, cb)) and synchronously (fs.xSync), which
// blocks this script until the host answers. Errors carry Node's codes (ENOENT, EEXIST, …) as well as the cluster's.
const NODE_CODES = Object.freeze({
  not_found: 'ENOENT', already_exists: 'EEXIST', not_a_directory: 'ENOTDIR', is_a_directory: 'EISDIR', not_empty: 'ENOTEMPTY',
  read_only: 'EROFS', reserved: 'EACCES', limit_exceeded: 'ENOSPC', insufficient_storage: 'ENOSPC', busy: 'EBUSY', timeout: 'ETIMEDOUT',
  invalid_argument: 'EINVAL', unavailable: 'ENOSYS', storage_unavailable: 'EIO', storage_disabled: 'EACCES',
});
const fsError = (e, syscall, path) => {
  const code = NODE_CODES[e?.code] ?? e?.code ?? 'EIO';
  const err = new Error(`${code}: ${e?.message ?? e}, ${syscall} '${path}'`);
  err.code = code;
  err.reason = e?.code;
  err.syscall = syscall;
  err.path = path;
  return err;
};
const stats = (s) => {
  const dir = s.kind === 'dir';
  const mtimeMs = s.modifiedAt ?? 0;
  return Object.freeze({
    size: s.size ?? 0, mtimeMs, mtime: new Date(mtimeMs), ctimeMs: mtimeMs, birthtimeMs: mtimeMs,
    mode: dir ? 0o40755 : 0o100644, contentType: s.contentType ?? null, etag: s.etag ?? null,
    isFile: () => !dir, isDirectory: () => dir, isSymbolicLink: () => false,
  });
};
const dirent = (e) => Object.freeze({ name: e.name, isFile: () => e.kind !== 'dir', isDirectory: () => e.kind === 'dir', isSymbolicLink: () => false });
const encodingOf = (options) => typeof options === 'string' ? options : options?.encoding ?? null;
const decoded = (bytes, options) => encodingOf(options) ? utf8Decode(bytes) : bytes;
const bytesOf = (data) => typeof data === 'string' ? utf8Encode(data) : toBytes(data);
const notFound = (path, syscall) => fsError({ code: 'not_found', message: 'no such file or directory' }, syscall, path);

// One definition per call: what it asks the host, and how the answer becomes Node's.
const calls = {
  readFile: { host: 'readFile', args: (path) => [String(path)], result: (r, [, options]) => decoded(r, options) },
  writeFile: { host: 'writeFile', args: (path, data, options) => [String(path), bytesOf(data), options?.contentType ?? null], result: () => undefined },
  appendFile: { host: 'appendFile', args: (path, data) => [String(path), bytesOf(data)], result: () => undefined },
  mkdir: { host: 'mkdir', args: (path, options) => [String(path), !!options?.recursive], result: () => undefined },
  readdir: { host: 'readdir', args: (path) => [String(path)], result: (r, [, options]) => r.map((e) => options?.withFileTypes ? dirent(e) : e.name) },
  stat: { host: 'stat', args: (path) => [String(path)], result: (r, [path]) => { if (r === null) throw notFound(path, 'stat'); return stats(r); } },
  rename: { host: 'rename', args: (from, to) => [String(from), String(to)], result: () => undefined },
  copyFile: { host: 'copyFile', args: (from, to, mode) => [String(from), String(to), !((mode ?? 0) & 1)], result: () => undefined },
  rmdir: { host: 'rmdir', args: (path, options) => [String(path), !!options?.recursive], result: () => undefined },
  unlink: { host: 'unlink', args: (path) => [String(path)], result: () => undefined },
};

const promises = {};
const sync = {};
for (const [name, call] of Object.entries(calls)) {
  promises[name] = async (...args) => {
    let r;
    try {
      r = await host.fs[call.host](...call.args(...args));
    } catch (e) {
      throw fsError(e, name, args[0]);
    }
    return call.result(r, args);
  };
  sync[name + 'Sync'] = (...args) => {
    const r = host.fs[call.host + 'Sync'](...call.args(...args));
    if (r instanceof Error) throw fsError(r, name, args[0]);
    return call.result(r, args);
  };
}
promises.lstat = promises.stat;
sync.lstatSync = sync.statSync;
promises.access = async (path) => { await promises.stat(path); };
sync.accessSync = (path) => { sync.statSync(path); };
sync.existsSync = (path) => {
  const r = host.fs.statSync(String(path));
  return !(r instanceof Error) && r !== null;
};
// rm: a file, or with recursive a folder and everything in it; force ignores what isn't there.
promises.rm = async (path, options) => {
  let s;
  try { s = await promises.stat(path); } catch (e) { if (options?.force && e.code === 'ENOENT') return; throw e; }
  if (s.isDirectory()) {
    if (!options?.recursive) throw fsError({ code: 'is_a_directory', message: 'is a directory (use recursive)' }, 'rm', path);
    return promises.rmdir(path, { recursive: true });
  }
  return promises.unlink(path);
};
sync.rmSync = (path, options) => {
  let s;
  try { s = sync.statSync(path); } catch (e) { if (options?.force && e.code === 'ENOENT') return; throw e; }
  if (s.isDirectory()) {
    if (!options?.recursive) throw fsError({ code: 'is_a_directory', message: 'is a directory (use recursive)' }, 'rm', path);
    return sync.rmdirSync(path, { recursive: true });
  }
  return sync.unlinkSync(path);
};

// File handles (D-080): the file's bytes are held here and edited, then published as one new version on close, so
// readers never see a half-made edit. Positional reads and writes, appending, truncating. Files up to 32 MB.
const MAX_HANDLE_BYTES = 32 * 1024 * 1024;
class Handle {
  constructor(path, flags, data, dirty) {
    this.path = path; this.flags = flags; this.data = data; this.length = data.length;
    this.pos = flags[0] === 'a' ? data.length : 0; this.dirty = dirty; this.closed = false;
  }
  check(syscall) { if (this.closed) throw fsError({ code: 'EBADF', message: 'file handle is closed' }, syscall, this.path); }
  writable(syscall) {
    this.check(syscall);
    if (this.flags[0] === 'r' && !this.flags.includes('+')) throw fsError({ code: 'EBADF', message: 'file is open for reading only' }, syscall, this.path);
  }
  room(size) {
    if (size > MAX_HANDLE_BYTES) throw fsError({ code: 'limit_exceeded', message: `files edited in place are limited to ${MAX_HANDLE_BYTES / 1048576} MB` }, 'write', this.path);
    if (size > this.data.length) {
      const grown = new Uint8Array(Math.min(MAX_HANDLE_BYTES, Math.max(size, this.data.length * 2, 1024)));
      grown.set(this.data.subarray(0, this.length));
      this.data = grown;
    }
  }
  read(buffer, offset = 0, length = buffer.length - offset, position = null) {
    this.check('read');
    const at = position ?? this.pos;
    const n = Math.max(0, Math.min(length, this.length - at));
    if (n > 0) buffer.set(this.data.subarray(at, at + n), offset);
    if (position === null || position === undefined) this.pos += n;
    return n;
  }
  write(bytes, position = null) {
    this.writable('write');
    const at = this.flags[0] === 'a' ? this.length : (position ?? this.pos);
    this.room(at + bytes.length);
    this.data.set(bytes, at);
    this.length = Math.max(this.length, at + bytes.length);
    if (this.flags[0] === 'a' || position === null || position === undefined) this.pos = at + bytes.length;
    this.dirty = true;
    return bytes.length;
  }
  truncate(length = 0) {
    this.writable('ftruncate');
    this.room(length);
    if (length > this.length) this.data.fill(0, this.length, length);
    this.length = length;
    this.dirty = true;
  }
  bytes() { return this.data.slice(0, this.length); }
  stats() { return stats({ kind: 'file', size: this.length, modifiedAt: Date.now() }); }
}
const writeArgs = (data, a, b, c) => {
  if (typeof data === 'string') return [utf8Encode(data), a ?? null];
  const bytes = toBytes(data);
  const offset = a ?? 0;
  return [bytes.subarray(offset, offset + (b ?? bytes.length - offset)), c ?? null];
};
const opened = async (path, flags, exists, read) => {
  path = String(path); flags = flags ?? 'r';
  const there = await exists(path);
  if (flags.includes('x') && there) throw fsError({ code: 'already_exists', message: 'file already exists' }, 'open', path);
  if (flags[0] === 'r' && !there) throw notFound(path, 'open');
  const keep = there && flags[0] !== 'w';
  return new Handle(path, flags, keep ? await read(path) : new Uint8Array(0), !keep);
};
const handleApi = (h, publish) => Object.freeze({
  fd: h,
  read: async (buffer, offset, length, position) => {
    if (buffer && !(buffer instanceof Uint8Array)) ({ buffer, offset, length, position } = buffer);
    buffer = buffer ?? new Uint8Array(16384);
    return { bytesRead: h.read(buffer, offset ?? 0, length ?? buffer.length - (offset ?? 0), position ?? null), buffer };
  },
  write: async (data, a, b, c) => { const [bytes, position] = writeArgs(data, a, b, c); return { bytesWritten: h.write(bytes, position), buffer: data }; },
  readFile: async (options) => decoded(h.bytes(), options),
  writeFile: async (data) => { h.truncate(0); h.write(bytesOf(data), 0); },
  appendFile: async (data) => { h.write(bytesOf(data), h.length); },
  truncate: async (length) => h.truncate(length ?? 0),
  stat: async () => h.stats(),
  close: async () => { if (!h.closed) { h.closed = true; if (h.dirty) await publish(h.path, h.bytes()); } },
});
promises.open = async (path, flags) => handleApi(
    await opened(path, flags, async (p) => (await host.fs.stat(p)) !== null, (p) => promises.readFile(p)),
    (p, bytes) => promises.writeFile(p, bytes));
// Numbers for the sync and callback forms, as Node's file descriptors.
const fds = new Map();
let nextFd = 3;
const fd = (n, syscall) => {
  const h = fds.get(n);
  if (!h) throw fsError({ code: 'EBADF', message: 'bad file descriptor' }, syscall, String(n));
  return h;
};
sync.openSync = (path, flags) => {
  path = String(path); flags = flags ?? 'r';
  const there = sync.existsSync(path);
  if (flags.includes('x') && there) throw fsError({ code: 'already_exists', message: 'file already exists' }, 'open', path);
  if (flags[0] === 'r' && !there) throw notFound(path, 'open');
  const keep = there && flags[0] !== 'w';
  const n = nextFd++;
  fds.set(n, new Handle(path, flags, keep ? sync.readFileSync(path) : new Uint8Array(0), !keep));
  return n;
};
sync.readSync = (n, buffer, offset, length, position) => {
  if (offset && typeof offset === 'object') ({ offset, length, position } = offset);
  return fd(n, 'read').read(buffer, offset ?? 0, length ?? buffer.length - (offset ?? 0), position ?? null);
};
sync.writeSync = (n, data, a, b, c) => { const [bytes, position] = writeArgs(data, a, b, c); return fd(n, 'write').write(bytes, position); };
sync.fstatSync = (n) => fd(n, 'fstat').stats();
sync.ftruncateSync = (n, length) => fd(n, 'ftruncate').truncate(length ?? 0);
sync.closeSync = (n) => {
  const h = fd(n, 'close');
  fds.delete(n);
  h.closed = true;
  if (h.dirty) sync.writeFileSync(h.path, h.bytes());
};

// Advisory locks (D-080): only cooperating code checks them. They expire on their own, and go when the job ends.
const lock = async (path, options) => {
  path = String(path);
  let lease;
  try { lease = await host.fs.lock(path, options?.ttlMs ?? 30000); } catch (e) { throw fsError(e, 'lock', path); }
  return Object.freeze({
    path, expiresAt: lease.expiresAt,
    renew: async (ttlMs) => (await host.fs.renewLock(path, lease.token, ttlMs ?? options?.ttlMs ?? 30000)).expiresAt,
    release: () => host.fs.unlock(path, lease.token),
  });
};

// Callback style, fs.readFile(path, [options], (err, data) => …), from the promises.
const callbacks = {};
for (const name of Object.keys(promises).filter((n) => n !== 'open')) {
  callbacks[name] = (...args) => {
    const cb = typeof args[args.length - 1] === 'function' ? args.pop() : null;
    const done = promises[name](...args);
    if (!cb) return done;
    done.then((value) => cb(null, value), (err) => cb(err));
  };
}

// fs.open and friends with callbacks work on descriptor numbers, as Node's do.
const later = (cb, work) => Promise.resolve().then(work).then((v) => cb(null, v), (e) => cb(e));
callbacks.open = (path, flags, cb) => { if (typeof flags === 'function') { cb = flags; flags = 'r'; } later(cb, () => sync.openSync(path, flags)); };
callbacks.read = (n, buffer, offset, length, position, cb) => later(cb, () => sync.readSync(n, buffer, offset, length, position));
callbacks.write = (n, data, ...rest) => { const cb = rest.pop(); later(cb, () => sync.writeSync(n, data, ...rest)); };
callbacks.close = (n, cb) => later(cb ?? (() => {}), () => sync.closeSync(n));

define('fs', Object.freeze({ ...callbacks, ...sync, lock, promises: Object.freeze(promises), constants: Object.freeze({ COPYFILE_EXCL: 1 }) }));
