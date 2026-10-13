// The global WebSocket, as Node's (undici 8.10's lib/web/websocket) (D-125), with CloseEvent and ErrorEvent. Loaded by the
// prelude when a script first touches one of them. The handshake is an HTTP/1.1 upgrade written over a net or tls socket
// by node:http (so it goes through the egress gate as a tcp connection, and TLS host names are checked as tls.connect
// does); frames are read and written here. As undici: incoming permessage-deflate is decompressed, nothing is compressed
// going out, and there's one frame per message.
(() => {
  const web = globalThis[Symbol.for('cluster93.web')];
  const { Blob } = web('blob');
  const { MessageEvent } = web('messaging');
  const encoder = new TextEncoder();
  const fatalDecoder = new TextDecoder('utf-8', { fatal: true });

  const GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';
  const CONNECTING = 0;
  const OPEN = 1;
  const CLOSING = 2;
  const CLOSED = 3;
  const SENT = 1;
  const RECEIVED = 2;
  const OP = { CONTINUATION: 0x0, TEXT: 0x1, BINARY: 0x2, CLOSE: 0x8, PING: 0x9, PONG: 0xa };
  const TAIL = new Uint8Array([0x00, 0x00, 0xff, 0xff]);
  const EMPTY = new Uint8Array(0);

  const argumentError = (prefix, min, length) =>
    new TypeError(`${prefix}: ${min} argument${min !== 1 ? 's' : ''} required, but${length ? ' only' : ''} ${length} found.`);
  const concat = (parts, total) => {
    const out = new Uint8Array(total ?? parts.reduce((n, p) => n + p.byteLength, 0));
    let at = 0;
    for (const part of parts) { out.set(part, at); at += part.byteLength; }
    return out;
  };
  const toBytes = (data) => (data instanceof ArrayBuffer ? new Uint8Array(data)
    : new Uint8Array(data.buffer, data.byteOffset, data.byteLength));
  const isArrayBuffer = (v) => v instanceof ArrayBuffer || Object.prototype.toString.call(v) === '[object ArrayBuffer]';

  // --- events ---

  class CloseEvent extends Event {
    #init;
    constructor(type, eventInitDict = {}) {
      if (arguments.length < 1) throw argumentError('CloseEvent constructor', 1, 0);
      super(String(type), eventInitDict ?? {});
      const init = eventInitDict ?? {};
      this.#init = { wasClean: Boolean(init.wasClean), code: init.code === undefined ? 0 : Number(init.code) & 0xffff,
        reason: init.reason === undefined ? '' : String(init.reason) };
    }
    get wasClean() { return this.#init.wasClean; }
    get code() { return this.#init.code; }
    get reason() { return this.#init.reason; }
    get [Symbol.toStringTag]() { return 'CloseEvent'; }
  }

  class ErrorEvent extends Event {
    #init;
    constructor(type, eventInitDict) {
      if (arguments.length < 1) throw argumentError('ErrorEvent constructor', 1, 0);
      super(String(type), eventInitDict ?? {});
      const init = eventInitDict ?? {};
      this.#init = { message: init.message === undefined ? '' : String(init.message), filename: init.filename === undefined ? '' : String(init.filename),
        lineno: init.lineno === undefined ? 0 : Number(init.lineno) >>> 0, colno: init.colno === undefined ? 0 : Number(init.colno) >>> 0, error: init.error };
    }
    get message() { return this.#init.message; }
    get filename() { return this.#init.filename; }
    get lineno() { return this.#init.lineno; }
    get colno() { return this.#init.colno; }
    get error() { return this.#init.error; }
    get [Symbol.toStringTag]() { return 'ErrorEvent'; }
  }

  // --- frames ---

  const mask = () => crypto.getRandomValues(new Uint8Array(4));
  // node:zlib, imported with the handshake's modules: compressed messages only come once it's done.
  let zlibModule = null;
  const zlib = () => zlibModule;

  /** A masked client frame, FIN set, as undici's WebsocketFrameSend. */
  function frame(opcode, body = EMPTY) {
    const length = body.byteLength;
    const offset = length > 65535 ? 14 : length > 125 ? 8 : 6;
    const out = new Uint8Array(offset + length);
    const key = mask();
    out[0] = 0x80 | opcode;
    out[1] = 0x80 | (length > 65535 ? 127 : length > 125 ? 126 : length);
    if (length > 65535) {
      const view = new DataView(out.buffer);
      view.setUint32(2, Math.floor(length / 2 ** 32));
      view.setUint32(6, length >>> 0);
    } else if (length > 125) {
      out[2] = length >>> 8;
      out[3] = length & 0xff;
    }
    out.set(key, offset - 4);
    for (let i = 0; i < length; i++) out[offset + i] = body[i] ^ key[i & 3];
    return out;
  }

  function isValidStatusCode(code) {
    if (code >= 1000 && code < 1015) return code !== 1004 && code !== 1005 && code !== 1006;
    return code >= 3000 && code <= 4999;
  }

  function isValidSubprotocol(protocol) {
    if (protocol.length === 0) return false;
    for (let i = 0; i < protocol.length; i++) {
      const c = protocol.charCodeAt(i);
      if (c < 0x21 || c > 0x7e || '"(),/:;<=>?@[\\]{}'.includes(protocol[i])) return false;
    }
    return true;
  }

  function parseExtensions(value) {
    const out = new Map();
    for (const part of value.split(';')) {
      const [name, v = ''] = part.split('=', 2);
      out.set(name.trim(), v.trim());
    }
    return out;
  }

  /** The URL record: http(s) becomes ws(s); anything else, or a fragment, is a SyntaxError. */
  function urlRecord(url) {
    let record;
    try {
      record = new URL(url);
    } catch (e) {
      throw new DOMException(String(e), 'SyntaxError');
    }
    if (record.protocol === 'http:') record.protocol = 'ws:';
    else if (record.protocol === 'https:') record.protocol = 'wss:';
    if (record.protocol !== 'ws:' && record.protocol !== 'wss:') throw new DOMException('expected a ws: or wss: url', 'SyntaxError');
    if (record.hash.length || record.href.endsWith('#')) throw new DOMException('hash', 'SyntaxError');
    return record;
  }

  /** webidl's unsigned short with [Clamp], rounding as undici does. */
  function clampCode(value) {
    let x = Number(value);
    if (Number.isNaN(x)) return 0;
    x = Math.min(Math.max(x, 0), 65535);
    return Math.floor(x) % 2 === 0 ? Math.floor(x) : Math.ceil(x);
  }

  /** The bytes of a USVString: lone surrogates become U+FFFD, as webidl converts them. */
  const usv = (value) => String(value).toWellFormed?.() ?? String(value);

  // --- the WebSocket ---

  const kHandler = Symbol('handler');

  class WebSocket extends EventTarget {
    #events = { open: null, error: null, close: null, message: null };
    #bufferedAmount = 0;
    #protocol = '';
    #extensions = '';
    #url;
    #binaryType = 'blob';
    #parser = null;
    #queue = null;

    constructor(url, protocols = []) {
      super();
      if (arguments.length < 1) throw argumentError('WebSocket constructor', 1, 0);
      let options;
      if (protocols !== null && typeof protocols === 'object' && !(Symbol.iterator in protocols)) {
        options = { protocols: protocols.protocols ?? [], headers: protocols.headers ?? null };
      } else {
        options = { protocols, headers: null };
      }
      protocols = typeof options.protocols === 'string' ? [options.protocols]
        : options.protocols !== null && typeof options.protocols === 'object' ? [...options.protocols].map(String) : [String(options.protocols)];
      const record = urlRecord(usv(url));
      if (protocols.length !== new Set(protocols.map((p) => p.toLowerCase())).size) {
        throw new DOMException('Invalid Sec-WebSocket-Protocol value', 'SyntaxError');
      }
      if (protocols.length > 0 && !protocols.every(isValidSubprotocol)) {
        throw new DOMException('Invalid Sec-WebSocket-Protocol value', 'SyntaxError');
      }
      this.#url = new URL(record.href);
      const handler = {
        readyState: CONNECTING,
        socket: null,
        closeState: new Set(),
        request: null,
        aborted: false,
        onSocketClose: () => this.#onSocketClose(),
        onMessage: (opcode, data) => this.#onMessage(opcode, data),
        established: (res, socket, head, extensions) => this.#established(res, socket, head, extensions),
      };
      this[kHandler] = handler;
      connect(handler, new URL(record.href), protocols, options.headers);
    }

    close(code = undefined, reason = undefined) {
      if (code !== undefined) code = clampCode(code);
      if (reason !== undefined) reason = usv(reason);
      closeConnection(this[kHandler], code ?? null, reason ?? '', true);
    }

    send(data) {
      if (arguments.length < 1) throw argumentError('WebSocket.send', 1, 0);
      const handler = this[kHandler];
      if (!(data instanceof Blob) && !isArrayBuffer(data) && !ArrayBuffer.isView(data)) data = usv(data);
      if (handler.readyState === CONNECTING) throw new DOMException('Sent before connected.', 'InvalidStateError');
      if (handler.readyState !== OPEN) return;
      if (typeof data === 'string') {
        const bytes = encoder.encode(data);
        this.#bufferedAmount += bytes.byteLength;
        this.#queue.add(Promise.resolve(frame(OP.TEXT, bytes)), () => { this.#bufferedAmount -= bytes.byteLength; });
      } else if (data instanceof Blob) {
        const size = data.size;
        this.#bufferedAmount += size;
        this.#queue.add(data.arrayBuffer().then((ab) => frame(OP.BINARY, new Uint8Array(ab))), () => { this.#bufferedAmount -= size; });
      } else {
        const bytes = toBytes(data);
        const length = data.byteLength;
        this.#bufferedAmount += length;
        this.#queue.add(Promise.resolve(frame(OP.BINARY, bytes)), () => { this.#bufferedAmount -= length; });
      }
    }

    get readyState() { return this[kHandler].readyState; }
    get bufferedAmount() { return this.#bufferedAmount; }
    get url() { return this.#url.href; }
    get extensions() { return this.#extensions; }
    get protocol() { return this.#protocol; }
    get binaryType() { return this.#binaryType; }
    set binaryType(type) { this.#binaryType = type === 'blob' || type === 'arraybuffer' ? type : 'blob'; }

    get onopen() { return this.#events.open; }
    set onopen(fn) { this.#setHandler('open', fn); }
    get onerror() { return this.#events.error; }
    set onerror(fn) { this.#setHandler('error', fn); }
    get onclose() { return this.#events.close; }
    set onclose(fn) { this.#setHandler('close', fn); }
    get onmessage() { return this.#events.message; }
    set onmessage(fn) { this.#setHandler('message', fn); }

    #setHandler(type, fn) {
      if (this.#events[type]) this.removeEventListener(type, this.#events[type]);
      if (typeof fn === 'function' || (fn !== null && typeof fn === 'object')) {
        this.addEventListener(type, fn);
        this.#events[type] = fn;
      } else {
        this.#events[type] = null;
      }
    }

    #established(res, socket, head, extensions) {
      const handler = this[kHandler];
      handler.socket = socket;
      this.#parser = new ByteParser(handler, extensions);
      this.#queue = new SendQueue(socket);
      handler.readyState = OPEN;
      if (res.headers['sec-websocket-extensions'] !== undefined) this.#extensions = res.headers['sec-websocket-extensions'];
      if (res.headers['sec-websocket-protocol'] !== undefined) this.#protocol = res.headers['sec-websocket-protocol'];
      socket.on('data', (chunk) => this.#parser.write(chunk));
      socket.on('close', () => handler.onSocketClose());
      socket.on('error', () => { handler.readyState = CLOSING; socket.destroy(); });
      this.dispatchEvent(new Event('open'));
      if (head?.length) this.#parser.write(head);
    }

    #onMessage(opcode, data) {
      if (this[kHandler].readyState !== OPEN) return;
      let value;
      if (opcode === OP.TEXT) {
        try {
          value = fatalDecoder.decode(data);
        } catch {
          failConnection(this[kHandler], 1007, 'Received invalid UTF-8 in text frame.');
          return;
        }
      } else if (this.#binaryType === 'blob') {
        value = new Blob([data]);
      } else {
        value = data.byteLength === data.buffer.byteLength ? data.buffer : new Uint8Array(data).buffer;
      }
      this.dispatchEvent(new MessageEvent('message', { origin: this.#url.origin, data: value }));
    }

    #onSocketClose() {
      const handler = this[kHandler];
      if (handler.readyState === CLOSED) return;
      const wasClean = handler.closeState.has(SENT) && handler.closeState.has(RECEIVED);
      let code = 1005;
      let reason = '';
      const result = this.#parser?.closingInfo;
      if (result && !result.error) {
        code = result.code ?? 1005;
        reason = result.reason;
      }
      handler.readyState = CLOSED;
      if (!handler.closeState.has(RECEIVED)) {
        code = 1006;
        this.dispatchEvent(new ErrorEvent('error', { error: new TypeError(reason) }));
      }
      this.dispatchEvent(new CloseEvent('close', { wasClean, code, reason }));
    }
  }

  for (const [name, value] of Object.entries({ CONNECTING, OPEN, CLOSING, CLOSED })) {
    Object.defineProperty(WebSocket, name, { value, enumerable: true, writable: false, configurable: false });
    Object.defineProperty(WebSocket.prototype, name, { value, enumerable: true, writable: false, configurable: false });
  }
  Object.defineProperty(WebSocket.prototype, Symbol.toStringTag, { value: 'WebSocket', configurable: true });

  // --- the connection ---

  /** The opening handshake, as undici's establishWebSocketConnection; it starts once the constructor has returned. */
  async function connect(handler, url, protocols, extraHeaders) {
    await null;
    const secure = url.protocol === 'wss:';
    let http;
    let nodeCrypto;
    try {
      const builtin = globalThis[Symbol.for('cluster93.builtin')];
      [http, nodeCrypto, zlibModule] = [builtin(secure ? 'node:https' : 'node:http'), builtin('node:crypto'), builtin('node:zlib')];
    } catch {
      failConnection(handler, 1002, 'Received network error or non-101 status code.');
      return;
    }
    if (handler.aborted) return;
    const key = nodeCrypto.randomBytes(16).toString('base64');
    const headers = [['host', url.host], ['connection', 'upgrade'], ['upgrade', 'websocket']];
    const given = new Set();
    if (extraHeaders) {
      // As undici's header list: names as they were given (a Headers instance's are lowercase), repeats joined.
      const pairs = extraHeaders instanceof Headers ? [...extraHeaders]
        : Symbol.iterator in Object(extraHeaders) ? [...extraHeaders].map(([name, value]) => [String(name), String(value)])
          : Object.entries(extraHeaders).map(([name, value]) => [name, String(value)]);
      new Headers(pairs);
      const byName = new Map();
      for (const [name, value] of pairs) {
        const lower = name.toLowerCase();
        const seen = byName.get(lower);
        if (seen) seen[1] = `${seen[1]}, ${value.trim()}`;
        else { const pair = [name, value.trim()]; byName.set(lower, pair); headers.push(pair); }
        given.add(lower);
      }
    }
    headers.push(['sec-websocket-key', key], ['sec-websocket-version', '13']);
    if (protocols.length) headers.push(['sec-websocket-protocol', protocols.join(', ')]);
    headers.push(['sec-websocket-extensions', 'permessage-deflate; client_max_window_bits']);
    for (const [name, value] of [['accept', '*/*'], ['accept-language', '*'], ['sec-fetch-mode', 'websocket'], ['user-agent', 'node'],
      ['pragma', 'no-cache'], ['cache-control', 'no-cache'], ['accept-encoding', 'gzip, deflate']]) {
      if (!given.has(name)) headers.push([name, value]);
    }
    const hostname = url.hostname.startsWith('[') ? url.hostname.slice(1, -1) : url.hostname;
    let req;
    try {
      req = http.request({ method: 'GET', hostname, port: url.port || (secure ? 443 : 80), path: `${url.pathname}${url.search}`,
        headers: headers.flat(), setHost: false });
    } catch {
      failConnection(handler, 1002, 'Received network error or non-101 status code.');
      return;
    }
    handler.request = req;
    const fail = () => { if (!handler.aborted) failConnection(handler, 1002, 'Received network error or non-101 status code.'); };
    req.on('error', fail);
    req.on('response', (res) => { res.resume(); fail(); });
    req.on('upgrade', (res, socket, head) => {
      if (handler.aborted) { socket.destroy(); return; }
      const failed = (reason) => { socket.destroy(); failConnection(handler, 1002, reason); };
      if (protocols.length !== 0 && !res.headers['sec-websocket-protocol']) return failed('Server did not respond with sent protocols.');
      if (String(res.headers.upgrade ?? '').toLowerCase() !== 'websocket') return failed('Server did not set Upgrade header to "websocket".');
      if (String(res.headers.connection ?? '').toLowerCase() !== 'upgrade') return failed('Server did not set Connection header to "upgrade".');
      const digest = nodeCrypto.createHash('sha1').update(key + GUID).digest('base64');
      if (res.headers['sec-websocket-accept'] !== digest) return failed('Incorrect hash received in Sec-WebSocket-Accept header.');
      let extensions;
      if (res.headers['sec-websocket-extensions'] !== undefined) {
        extensions = parseExtensions(res.headers['sec-websocket-extensions']);
        if (!extensions.has('permessage-deflate')) return failed('Sec-WebSocket-Extensions header does not match.');
      }
      const protocol = res.headers['sec-websocket-protocol'];
      if (protocol !== undefined && !protocols.includes(protocol)) return failed('Protocol was not set in the opening handshake.');
      handler.established(res, socket, head, extensions);
    });
    req.end();
  }

  /** As undici's closeWebSocketConnection. */
  function closeConnection(handler, code, reason, validate = false) {
    if (validate) {
      if (code !== null && code !== 1000 && (code < 3000 || code > 4999)) throw new DOMException('invalid code', 'InvalidAccessError');
      if (reason !== null) {
        const length = encoder.encode(reason).byteLength;
        if (length > 123) throw new DOMException(`Reason must be less than 123 bytes; received ${length}`, 'SyntaxError');
      }
    }
    if (handler.readyState === CLOSED || handler.readyState === CLOSING) {
      // nothing to do
    } else if (handler.readyState !== OPEN) {
      failConnection(handler);
      handler.readyState = CLOSING;
    } else if (!handler.closeState.has(SENT) && !handler.closeState.has(RECEIVED)) {
      if (reason.length !== 0 && code === null) code = 1000;
      let body = EMPTY;
      if (code !== null) {
        const text = encoder.encode(reason ?? '');
        body = new Uint8Array(2 + text.byteLength);
        body[0] = code >>> 8;
        body[1] = code & 0xff;
        body.set(text, 2);
      }
      handler.socket.write(frame(OP.CLOSE, body));
      handler.closeState.add(SENT);
      handler.readyState = CLOSING;
    } else {
      handler.readyState = CLOSING;
    }
  }

  /** As undici's failWebsocketConnection. */
  function failConnection(handler, code, reason) {
    if (handler.readyState === OPEN) closeConnection(handler, code, reason, false);
    handler.aborted = true;
    if (handler.request && !handler.socket) handler.request.destroy();
    if (handler.readyState === CONNECTING) handler.onSocketClose();
    else if (handler.socket && !handler.socket.destroyed) handler.socket.destroy();
  }

  // --- sending ---

  /** Frames go out in the order they were sent, a Blob's once it's read. */
  class SendQueue {
    #socket;
    #pending = Promise.resolve();
    constructor(socket) { this.#socket = socket; }
    add(framed, done) {
      this.#pending = this.#pending.then(() => framed).then((bytes) => {
        if (!this.#socket.destroyed) this.#socket.write(bytes, () => done());
      }, () => {});
    }
  }

  // --- receiving ---

  /** As undici's ByteParser: frames from the socket, messages to the WebSocket, control frames answered. */
  class ByteParser {
    #handler;
    #buffers = [];
    #length = 0;
    #state = 'info';
    #info = {};
    #fragments = [];
    #fragmentsBytes = 0;
    #inflate = null;
    #deflateOptions = null;
    #busy = false;
    #stopped = false;

    constructor(handler, extensions) {
      this.#handler = handler;
      if (extensions?.has('permessage-deflate')) this.#deflateOptions = { serverMaxWindowBits: extensions.get('server_max_window_bits') };
    }

    get closingInfo() { return this.#info.closeInfo; }

    write(chunk) {
      const bytes = new Uint8Array(chunk.buffer, chunk.byteOffset, chunk.byteLength);
      this.#buffers.push(bytes);
      this.#length += bytes.byteLength;
      this.#run();
    }

    #consume(n) {
      if (n === 0) return EMPTY;
      this.#length -= n;
      const first = this.#buffers[0];
      if (first.byteLength > n) {
        this.#buffers[0] = first.subarray(n);
        return first.subarray(0, n);
      }
      if (first.byteLength === n) return this.#buffers.shift();
      const out = new Uint8Array(n);
      let at = 0;
      while (at < n) {
        const next = this.#buffers[0];
        const take = Math.min(next.byteLength, n - at);
        out.set(next.subarray(0, take), at);
        at += take;
        if (take === next.byteLength) this.#buffers.shift();
        else this.#buffers[0] = next.subarray(take);
      }
      return out;
    }

    #fail(code, reason) {
      this.#stopped = true;
      failConnection(this.#handler, code, reason);
    }

    #run() {
      while (!this.#busy && !this.#stopped) {
        if (this.#state === 'info') {
          if (this.#length < 2) return;
          const head = this.#consume(2);
          const fin = (head[0] & 0x80) !== 0;
          const opcode = head[0] & 0x0f;
          const masked = (head[1] & 0x80) === 0x80;
          const fragmented = !fin && opcode !== OP.CONTINUATION;
          const payloadLength = head[1] & 0x7f;
          const rsv1 = head[0] & 0x40;
          const control = opcode === OP.CLOSE || opcode === OP.PING || opcode === OP.PONG;
          const textOrBinary = opcode === OP.TEXT || opcode === OP.BINARY;
          if (!textOrBinary && !control && opcode !== OP.CONTINUATION) return this.#fail(1002, 'Invalid opcode received');
          if (masked) return this.#fail(1002, 'Frame cannot be masked');
          if (rsv1 !== 0 && !this.#deflateOptions) return this.#fail(1002, 'Expected RSV1 to be clear.');
          if ((head[0] & 0x30) !== 0) return this.#fail(1002, 'RSV1, RSV2, RSV3 must be clear');
          if (fragmented && !textOrBinary) return this.#fail(1002, 'Invalid frame type was fragmented.');
          if (textOrBinary && this.#fragments.length > 0) return this.#fail(1002, 'Expected continuation frame');
          if (this.#info.fragmented && fragmented) return this.#fail(1002, 'Fragmented frame exceeded 125 bytes.');
          if ((payloadLength > 125 || fragmented) && control) return this.#fail(1002, 'Control frame either too large or fragmented');
          if (opcode === OP.CONTINUATION && this.#fragments.length === 0 && !this.#info.compressed) {
            return this.#fail(1002, 'Unexpected continuation frame');
          }
          if (payloadLength <= 125) {
            this.#info.payloadLength = payloadLength;
            this.#state = 'data';
          } else {
            this.#state = payloadLength === 126 ? 'length16' : 'length64';
          }
          if (textOrBinary) {
            this.#info.binaryType = opcode;
            this.#info.compressed = rsv1 !== 0;
          }
          this.#info.opcode = opcode;
          this.#info.fin = fin;
          this.#info.fragmented = fragmented;
        } else if (this.#state === 'length16') {
          if (this.#length < 2) return;
          const b = this.#consume(2);
          this.#info.payloadLength = (b[0] << 8) | b[1];
          this.#state = 'data';
        } else if (this.#state === 'length64') {
          if (this.#length < 8) return;
          const b = this.#consume(8);
          const view = new DataView(b.buffer, b.byteOffset, 8);
          const upper = view.getUint32(0);
          const lower = view.getUint32(4);
          if (upper !== 0 || lower > 2 ** 31 - 1) return this.#fail(1009, 'Received payload length > 2^31 bytes.');
          this.#info.payloadLength = lower;
          this.#state = 'data';
        } else {
          if (this.#length < this.#info.payloadLength) return;
          const body = this.#consume(this.#info.payloadLength);
          const { opcode } = this.#info;
          if (opcode === OP.CLOSE || opcode === OP.PING || opcode === OP.PONG) {
            this.#state = 'info';
            if (!this.#control(body)) { this.#stopped = true; return; }
          } else if (!this.#info.compressed) {
            this.#fragmentsBytes += body.byteLength;
            this.#fragments.push(body);
            if (!this.#info.fragmented && this.#info.fin) this.#handler.onMessage(this.#info.binaryType, this.#takeFragments());
            this.#state = 'info';
          } else {
            this.#busy = true;
            this.#decompress(body, this.#info.fin, (error, data) => {
              this.#busy = false;
              if (error) return this.#fail(1007, error.message);
              this.#fragmentsBytes += data.byteLength;
              this.#fragments.push(data);
              this.#state = 'info';
              if (this.#info.fin) this.#handler.onMessage(this.#info.binaryType, this.#takeFragments());
              this.#run();
            });
            return;
          }
        }
      }
    }

    #takeFragments() {
      const fragments = this.#fragments;
      const total = this.#fragmentsBytes;
      this.#fragments = [];
      this.#fragmentsBytes = 0;
      return fragments.length === 1 ? fragments[0] : concat(fragments, total);
    }

    #decompress(chunk, fin, callback) {
      if (!this.#inflate) {
        let windowBits = 15;
        const asked = this.#deflateOptions.serverMaxWindowBits;
        if (asked) {
          if (!/^\d+$/.test(asked) || Number(asked) < 8 || Number(asked) > 15) { callback(new Error('Invalid server_max_window_bits')); return; }
          windowBits = Number.parseInt(asked, 10);
        }
        try {
          this.#inflate = zlib().createInflateRaw({ windowBits });
        } catch (err) {
          callback(err);
          return;
        }
        this.#inflate.chunks = [];
        this.#inflate.on('data', (data) => this.#inflate.chunks.push(new Uint8Array(data.buffer, data.byteOffset, data.byteLength)));
        this.#inflate.on('error', (err) => { this.#inflate = null; callback(err); });
      }
      this.#inflate.write(chunk);
      if (fin) this.#inflate.write(TAIL);
      this.#inflate.flush(() => {
        if (!this.#inflate) return;
        const full = concat(this.#inflate.chunks);
        this.#inflate.chunks = [];
        callback(null, full);
      });
    }

    #parseCloseBody(data) {
      let code;
      if (data.byteLength >= 2) code = (data[0] << 8) | data[1];
      if (code !== undefined && !isValidStatusCode(code)) return { code: 1002, reason: 'Invalid status code', error: true };
      let reason = data.subarray(2);
      if (reason[0] === 0xef && reason[1] === 0xbb && reason[2] === 0xbf) reason = reason.subarray(3);
      try {
        reason = fatalDecoder.decode(reason);
      } catch {
        return { code: 1007, reason: 'Invalid UTF-8', error: true };
      }
      return { code, reason, error: false };
    }

    #control(body) {
      const handler = this.#handler;
      const { opcode, payloadLength } = this.#info;
      if (opcode === OP.CLOSE) {
        if (payloadLength === 1) { failConnection(handler, 1002, 'Received close frame with a 1-byte body.'); return false; }
        this.#info.closeInfo = this.#parseCloseBody(body);
        if (this.#info.closeInfo.error) {
          failConnection(handler, this.#info.closeInfo.code, this.#info.closeInfo.reason);
          return false;
        }
        if (!handler.closeState.has(SENT) && !handler.closeState.has(RECEIVED)) {
          let echo = EMPTY;
          if (this.#info.closeInfo.code) echo = new Uint8Array([this.#info.closeInfo.code >>> 8, this.#info.closeInfo.code & 0xff]);
          handler.socket.write(frame(OP.CLOSE, echo));
          handler.closeState.add(SENT);
        }
        handler.readyState = CLOSING;
        handler.closeState.add(RECEIVED);
        return false;
      }
      if (opcode === OP.PING && !handler.closeState.has(RECEIVED)) handler.socket.write(frame(OP.PONG, body));
      return true;
    }
  }

  return { WebSocket, CloseEvent, ErrorEvent };
})()
