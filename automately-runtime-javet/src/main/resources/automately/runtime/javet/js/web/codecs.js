// TextEncoderStream, TextDecoderStream, CompressionStream and DecompressionStream, as Node's (D-124): transform
// streams over the isolate's TextEncoder/TextDecoder and the host's zlib.
(() => {
  const { TransformStream } = globalThis;
  const zlib = globalThis[Symbol.for('automately.zlib')];
  const kState = Symbol('state');

  class TextEncoderStream {
    constructor() {
      const encoder = new TextEncoder();
      let pending = '';
      this[kState] = new TransformStream({
        transform(chunk, controller) {
          let text = pending + String(chunk);
          pending = '';
          // A lone high surrogate at the end waits for its pair, as the Encoding spec's encoder does.
          const last = text.charCodeAt(text.length - 1);
          if (last >= 0xd800 && last <= 0xdbff) { pending = text.slice(-1); text = text.slice(0, -1); }
          if (text) controller.enqueue(encoder.encode(text));
        },
        flush(controller) { if (pending) controller.enqueue(new Uint8Array([0xef, 0xbf, 0xbd])); },
      });
    }
    get encoding() { return 'utf-8'; }
    get readable() { return this[kState].readable; }
    get writable() { return this[kState].writable; }
  }

  class TextDecoderStream {
    constructor(label = 'utf-8', options = {}) {
      const decoder = new TextDecoder(label, options);
      this[kState] = { decoder, stream: new TransformStream({
        transform(chunk, controller) {
          const text = decoder.decode(chunk, { stream: true });
          if (text) controller.enqueue(text);
        },
        flush(controller) {
          const text = decoder.decode();
          if (text) controller.enqueue(text);
        },
      }) };
    }
    get encoding() { return this[kState].decoder.encoding; }
    get fatal() { return this[kState].decoder.fatal; }
    get ignoreBOM() { return this[kState].decoder.ignoreBOM; }
    get readable() { return this[kState].stream.readable; }
    get writable() { return this[kState].stream.writable; }
  }

  const FORMATS = { gzip: ['gzip', 'gunzip'], deflate: ['deflate', 'inflate'], 'deflate-raw': ['deflateRaw', 'inflateRaw'],
    brotli: ['brotliCompress', 'brotliDecompress'] };
  const bytes = (chunk, what) => {
    if (chunk instanceof Uint8Array) return chunk;
    if (ArrayBuffer.isView(chunk)) return new Uint8Array(chunk.buffer, chunk.byteOffset, chunk.byteLength);
    if (chunk instanceof ArrayBuffer) return new Uint8Array(chunk);
    throw Object.assign(new TypeError(`The "chunk" argument must be of type ArrayBuffer, Buffer, TypedArray or DataView. Received ${typeof chunk}`),
      { code: 'ERR_INVALID_ARG_TYPE' });
  };
  const zlibStream = (format, which, name) => {
    const kinds = FORMATS[format];
    if (!kinds) {
      throw Object.assign(new TypeError(`Failed to construct '${name}': 1st argument '${format}' is not a valid enum value of type CompressionFormat.`),
        { code: 'ERR_INVALID_ARG_VALUE' });
    }
    let handle = null;
    return new TransformStream({
      start() { handle = zlib.open(kinds[which], {}); },
      transform(chunk, controller) {
        const out = zlib.write(handle, bytes(chunk));
        if (out && out.byteLength) controller.enqueue(new Uint8Array(out));
      },
      flush(controller) {
        const out = zlib.finish(handle);
        if (out && out.byteLength) controller.enqueue(new Uint8Array(out));
        zlib.close(handle);
      },
    });
  };

  class CompressionStream {
    constructor(format) { this[kState] = zlibStream(String(format), 0, 'CompressionStream'); }
    get readable() { return this[kState].readable; }
    get writable() { return this[kState].writable; }
  }

  class DecompressionStream {
    constructor(format) { this[kState] = zlibStream(String(format), 1, 'DecompressionStream'); }
    get readable() { return this[kState].readable; }
    get writable() { return this[kState].writable; }
  }

  return { TextEncoderStream, TextDecoderStream, CompressionStream, DecompressionStream };
})()
