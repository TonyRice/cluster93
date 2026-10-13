// Blob and File, as Node's (D-124). Small and on their own, so node:buffer can export them without loading the rest of
// the WHATWG classes; stream() reaches for ReadableStream only when it's called.
(() => {
  const encoder = new TextEncoder();
  const kState = Symbol('state');
  const typeError = (message, code) => Object.assign(new TypeError(message), code ? { code } : {});
  const describe = (value) => {
    if (value === null) return 'null';
    if (value === undefined) return 'undefined';
    if (typeof value === 'function') return `function ${value.name}`;
    if (typeof value === 'object') return `an instance of ${value.constructor?.name ?? 'Object'}`;
    return `type ${typeof value} (${typeof value === 'string' ? `'${value}'` : String(value)})`;
  };
  const concat = (parts) => {
    let length = 0;
    for (const part of parts) length += part.byteLength;
    const out = new Uint8Array(length);
    let at = 0;
    for (const part of parts) { out.set(part, at); at += part.byteLength; }
    return out;
  };
  const bytesOf = (view) => (view instanceof ArrayBuffer ? new Uint8Array(view.slice(0))
    : new Uint8Array(view.buffer.slice(view.byteOffset, view.byteOffset + view.byteLength)));
  const utf8 = (bytes) => new TextDecoder().decode(bytes);

  const blobBytes = new WeakMap();

  class Blob {
    constructor(blobParts = [], options = {}) {
      if (blobParts === null || typeof blobParts !== 'object' || typeof blobParts[Symbol.iterator] !== 'function') {
        throw typeError('Value cannot be converted to sequence.', 'ERR_INVALID_ARG_TYPE');
      }
      if (options !== undefined && options !== null && typeof options !== 'object' && typeof options !== 'function') {
        throw typeError(`The "options" argument must be of type object. Received ${describe(options)}`, 'ERR_INVALID_ARG_TYPE');
      }
      const endings = options?.endings ?? 'transparent';
      if (endings !== 'transparent' && endings !== 'native') {
        throw typeError(`The property 'options.endings' is invalid. Received '${endings}'`, 'ERR_INVALID_ARG_VALUE');
      }
      const parts = [];
      for (const part of blobParts) {
        if (part instanceof Blob) parts.push(blobBytes.get(part));
        else if (part instanceof ArrayBuffer || ArrayBuffer.isView(part)) parts.push(bytesOf(part));
        else {
          let text = String(part);
          if (endings === 'native') text = text.replace(/\r\n|\r/g, '\n');
          parts.push(encoder.encode(text));
        }
      }
      blobBytes.set(this, concat(parts));
      const type = options?.type === undefined ? '' : String(options.type);
      this[kState] = { type: /[^ -~]/.test(type) ? '' : type.toLowerCase() };
    }
    get size() { return blobBytes.get(this).byteLength; }
    get type() { return this[kState].type; }
    slice(start = 0, end = this.size, contentType = '') {
      const size = this.size;
      const from = start < 0 ? Math.max(size + start, 0) : Math.min(start, size);
      const to = end < 0 ? Math.max(size + end, 0) : Math.min(end, size);
      const blob = new Blob([], { type: contentType });
      blobBytes.set(blob, blobBytes.get(this).slice(from, Math.max(from, to)));
      return blob;
    }
    async arrayBuffer() { return blobBytes.get(this).slice().buffer; }
    async bytes() { return blobBytes.get(this).slice(); }
    async text() { return utf8(blobBytes.get(this)); }
    stream() {
      const bytes = blobBytes.get(this);
      let at = 0;
      return new globalThis.ReadableStream({
        type: 'bytes',
        pull(controller) {
          if (at >= bytes.byteLength) { controller.close(); return; }
          const end = Math.min(at + 65536, bytes.byteLength);
          controller.enqueue(bytes.slice(at, end));
          at = end;
        },
      });
    }
    get [Symbol.toStringTag]() { return 'Blob'; }
  }

  class File extends Blob {
    constructor(fileBits, fileName, options = {}) {
      if (arguments.length < 2) throw typeError('The "fileBits" and "fileName" arguments must be specified', 'ERR_MISSING_ARGS');
      super(fileBits, options);
      this[kState].name = String(fileName);
      this[kState].lastModified = options?.lastModified === undefined ? Date.now() : Number(options.lastModified);
    }
    get name() { return this[kState].name; }
    get lastModified() { return this[kState].lastModified; }
    get [Symbol.toStringTag]() { return 'File'; }
  }

  return { Blob, File, blobBytes };
})()
