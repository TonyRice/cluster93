// node:fs: the isolate's fs (js/fs.js, D-079) as a module, plus createReadStream and createWriteStream as Node streams
// (D-109), over the same file handles: so a stream holds the whole file, and files are limited to 32 MB, as handles are.
// For bigger files, use files.stream and files.write, which stream from and to storage.
import { Readable, Writable } from 'node:stream';
import { Buffer } from 'node:buffer';

const fs = globalThis.fs;
const CHUNK = 64 * 1024;

const pathOf = (path) => (path instanceof URL ? decodeURIComponent(path.pathname) : Buffer.isBuffer(path) ? path.toString() : String(path));
const optionsOf = (options) => (typeof options === 'string' ? { encoding: options } : { ...(options ?? {}) });

export class ReadStream extends Readable {
  constructor(path, options) {
    options = optionsOf(options);
    super({ highWaterMark: options.highWaterMark ?? CHUNK, encoding: options.encoding, signal: options.signal,
      emitClose: options.emitClose ?? true, autoDestroy: options.autoClose ?? true });
    this.path = pathOf(path);
    this.flags = options.flags ?? 'r';
    this.start = options.start;
    this.end = options.end ?? Infinity;
    this.pos = this.start ?? 0;
    this.bytesRead = 0;
    this.pending = true;
    this.fd = null;
    this._handle = null;
  }
  _construct(callback) {
    fs.promises.open(this.path, this.flags).then((handle) => {
      this._handle = handle;
      this.fd = handle.fd;
      this.pending = false;
      this.emit('open', this.fd);
      this.emit('ready');
      callback();
    }, callback);
  }
  _read(size) {
    const want = Math.min(size || CHUNK, this.end === Infinity ? Infinity : this.end - this.pos + 1);
    if (want <= 0) { this.push(null); return; }
    const buffer = Buffer.alloc(Math.min(want, CHUNK * 16));
    this._handle.read(buffer, 0, buffer.length, this.pos).then(({ bytesRead }) => {
      if (bytesRead === 0) { this.push(null); return; }
      this.pos += bytesRead;
      this.bytesRead += bytesRead;
      this.push(buffer.subarray(0, bytesRead));
    }, (err) => this.destroy(err));
  }
  _destroy(err, callback) {
    const handle = this._handle;
    this._handle = null;
    if (!handle) { callback(err); return; }
    handle.close().then(() => callback(err), (closeErr) => callback(err ?? closeErr));
  }
  close(callback) { if (callback) this.once('close', callback); this.destroy(); }
}

export class WriteStream extends Writable {
  constructor(path, options) {
    options = optionsOf(options);
    super({ highWaterMark: options.highWaterMark, decodeStrings: true, defaultEncoding: options.encoding ?? 'utf8',
      signal: options.signal, emitClose: options.emitClose ?? true, autoDestroy: options.autoClose ?? true });
    this.path = pathOf(path);
    this.flags = options.flags ?? 'w';
    this.start = options.start;
    this.pos = this.start;
    this.bytesWritten = 0;
    this.pending = true;
    this.fd = null;
    this._handle = null;
  }
  _construct(callback) {
    fs.promises.open(this.path, this.flags).then((handle) => {
      this._handle = handle;
      this.fd = handle.fd;
      this.pending = false;
      this.emit('open', this.fd);
      this.emit('ready');
      callback();
    }, callback);
  }
  _write(chunk, encoding, callback) {
    const at = this.flags[0] === 'a' ? null : this.pos ?? this.bytesWritten;
    this._handle.write(chunk, 0, chunk.length, at).then(({ bytesWritten }) => {
      this.bytesWritten += bytesWritten;
      if (this.pos !== undefined) this.pos += bytesWritten;
      callback();
    }, callback);
  }
  // Closing publishes the file, as a handle's close does: readers never see half of it.
  _final(callback) {
    const handle = this._handle;
    this._handle = null;
    handle.close().then(() => callback(), callback);
  }
  _destroy(err, callback) {
    const handle = this._handle;
    this._handle = null;
    if (!handle) { callback(err); return; }
    handle.close().then(() => callback(err), (closeErr) => callback(err ?? closeErr));
  }
  close(callback) { if (callback) this.once('close', callback); this.end(); }
}

export function createReadStream(path, options) { return new ReadStream(path, options); }
export function createWriteStream(path, options) { return new WriteStream(path, options); }

export const { /* automately:fs-names */ } = fs;
export default { ...fs, createReadStream, createWriteStream, ReadStream, WriteStream };
