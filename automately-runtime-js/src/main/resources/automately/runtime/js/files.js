// files: FilesBinding over the tenant's file storage. Uploads stream chunk by chunk; files.stream() can be returned
// from a request handler and is then served by Java straight from storage (Range, ETag).
const FILE = Symbol('automately.file');
const openFile = async (path) => {
  const r = await host.files.open(String(path));
  return { file: r.file, body: readable(r.streamId) };
};
const writeParts = async (writer, data) => {
  const put = (part) => host.files.write(writer, toBytes(part));
  if (data === undefined || data === null) return;
  if (typeof data === 'string' || data instanceof Uint8Array || data instanceof ArrayBuffer || ArrayBuffer.isView(data)) {
    await put(data);
  } else if (data && (typeof data[Symbol.asyncIterator] === 'function' || typeof data[Symbol.iterator] === 'function')) {
    for await (const part of data) if (part !== undefined && part !== null) await put(part);
  } else {
    await put(JSON.stringify(data));
  }
};
define('files', Object.freeze({
  async write(path, data, options) {
    const contentType = options?.contentType ?? (typeof data === 'object' && !(data instanceof Uint8Array) && !isIterable(data) && data !== null
        ? 'application/json' : null);
    const writer = await host.files.create(String(path), contentType);
    try {
      await writeParts(writer, data?.body && typeof data.body[Symbol.asyncIterator] === 'function' && !isIterable(data) ? data.body : data);
      return await host.files.finish(writer);
    } catch (e) {
      host.files.abort(writer);
      throw e;
    }
  },
  read: async (path) => collect((await openFile(path)).body, BUFFER_LIMIT, `File ${path}`),
  text: async (path) => utf8Decode(await collect((await openFile(path)).body, BUFFER_LIMIT, `File ${path}`)),
  json: async (path) => JSON.parse(utf8Decode(await collect((await openFile(path)).body, BUFFER_LIMIT, `File ${path}`))),
  stream(path, options) {
    const ref = { path: String(path), [FILE]: { path: String(path), headers: options?.headers ?? {} } };
    ref[Symbol.asyncIterator] = () => {
      let inner = null;
      return {
        async next() {
          if (!inner) inner = (await openFile(path)).body[Symbol.asyncIterator]();
          return inner.next();
        },
        async return() { return inner ? inner.return() : { value: undefined, done: true }; },
      };
    };
    return Object.freeze(ref);
  },
  stat: (path) => host.files.stat(String(path)),
  list: (options) => host.files.list(options),
  delete: (path) => host.files.delete(String(path)),
}));
