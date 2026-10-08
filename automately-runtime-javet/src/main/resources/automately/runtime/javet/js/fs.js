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

// Callback style, fs.readFile(path, [options], (err, data) => …), from the promises.
const callbacks = {};
for (const name of Object.keys(promises)) {
  callbacks[name] = (...args) => {
    const cb = typeof args[args.length - 1] === 'function' ? args.pop() : null;
    const done = promises[name](...args);
    if (!cb) return done;
    done.then((value) => cb(null, value), (err) => cb(err));
  };
}

define('fs', Object.freeze({ ...callbacks, ...sync, promises: Object.freeze(promises), constants: Object.freeze({ COPYFILE_EXCL: 1 }) }));
