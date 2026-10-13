// The WHATWG fetch classes, as Node's (undici's) (D-124): Blob, File, Headers, Request, Response, FormData and fetch.
// Loaded by the prelude when a script first touches one of them. fetch goes through the host's streamed request (the
// same egress gate, limits and connections as node:http), following redirects itself so each hop is checked by the
// gate, and its bodies are ReadableStreams.
(() => {
  const { ReadableStream } = globalThis;
  const httpHost = globalThis[Symbol.for('cluster93.http')];
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

  // Blob and File (web/blob.js), and the bytes behind each.
  const { Blob, File, blobBytes } = globalThis[Symbol.for('cluster93.web')]('blob');

  // --- Headers ---

  const TOKEN = /^[!#$%&'*+\-.^_`|~0-9A-Za-z]+$/;
  const trimValue = (value) => String(value).replace(/^[\t\n\r ]+|[\t\n\r ]+$/g, '');

  class Headers {
    constructor(init = undefined) {
      this[kState] = { list: [], guard: 'none' };
      if (init === undefined || init === null) return;
      if (typeof init !== 'object' && typeof init !== 'function') {
        throw typeError("Headers constructor: Expected init to be one of: sequence<sequence<ByteString>>, record<ByteString, ByteString>.");
      }
      if (init instanceof Headers) {
        for (const [name, value] of init[kState].list) this.append(name, value);
      } else if (typeof init[Symbol.iterator] === 'function') {
        for (const pair of init) {
          const entry = [...pair];
          if (entry.length !== 2) throw typeError(`Headers constructor: expected name/value pair to be length 2, found ${entry.length}.`);
          this.append(entry[0], entry[1]);
        }
      } else {
        for (const key of Reflect.ownKeys(init)) {
          if (typeof key === 'symbol') continue;
          const descriptor = Reflect.getOwnPropertyDescriptor(init, key);
          if (descriptor?.enumerable) this.append(key, init[key]);
        }
      }
    }
    #check(method, name, value) {
      if (!TOKEN.test(String(name))) throw typeError(`Headers.${method}: "${name}" is an invalid header name.`);
      if (value !== undefined && /[\0\r\n]/.test(value)) throw typeError(`Headers.${method}: "${value}" is an invalid header value.`);
      if (this[kState].guard === 'immutable') throw typeError('immutable');
    }
    append(name, value) {
      const normalized = trimValue(value);
      this.#check('append', name, normalized);
      this[kState].list.push([String(name), normalized]);
    }
    delete(name) {
      this.#check('delete', name);
      const lower = String(name).toLowerCase();
      this[kState].list = this[kState].list.filter(([n]) => n.toLowerCase() !== lower);
    }
    get(name) {
      if (!TOKEN.test(String(name))) throw typeError(`Headers.get: "${name}" is an invalid header name.`);
      const lower = String(name).toLowerCase();
      const values = this[kState].list.filter(([n]) => n.toLowerCase() === lower).map(([, v]) => v);
      return values.length ? values.join(', ') : null;
    }
    getSetCookie() {
      return this[kState].list.filter(([n]) => n.toLowerCase() === 'set-cookie').map(([, v]) => v);
    }
    has(name) {
      if (!TOKEN.test(String(name))) throw typeError(`Headers.has: "${name}" is an invalid header name.`);
      const lower = String(name).toLowerCase();
      return this[kState].list.some(([n]) => n.toLowerCase() === lower);
    }
    set(name, value) {
      const normalized = trimValue(value);
      this.#check('set', name, normalized);
      const lower = String(name).toLowerCase();
      const list = this[kState].list;
      const first = list.findIndex(([n]) => n.toLowerCase() === lower);
      if (first < 0) { list.push([String(name), normalized]); return; }
      list[first] = [list[first][0], normalized];
      this[kState].list = list.filter(([n], i) => i <= first || n.toLowerCase() !== lower);
    }
    /** The sorted, combined view iteration gives: names lowercased, set-cookie kept apart. */
    #sorted() {
      const names = [...new Set(this[kState].list.map(([n]) => n.toLowerCase()))].sort();
      const out = [];
      for (const name of names) {
        if (name === 'set-cookie') for (const value of this.getSetCookie()) out.push([name, value]);
        else out.push([name, this.get(name)]);
      }
      return out;
    }
    forEach(callback, thisArg) {
      if (typeof callback !== 'function') throw typeError("Headers.forEach: Expected callback to be a function.");
      for (const [name, value] of this.#sorted()) callback.call(thisArg, value, name, this);
    }
    *entries() { yield* this.#sorted(); }
    *keys() { for (const [name] of this.#sorted()) yield name; }
    *values() { for (const [, value] of this.#sorted()) yield value; }
    [Symbol.iterator]() { return this.entries(); }
    get [Symbol.toStringTag]() { return 'Headers'; }
  }
  const immutable = (headers) => { headers[kState].guard = 'immutable'; return headers; };

  // --- FormData ---

  const toEntry = (name, value, filename) => {
    if (value instanceof Blob) {
      const file = value instanceof File && filename === undefined ? value
        : new File([value], filename !== undefined ? String(filename) : value instanceof File ? value.name : 'blob',
          { type: value.type, lastModified: value instanceof File ? value.lastModified : undefined });
      return [String(name), file];
    }
    return [String(name), String(value)];
  };

  class FormData {
    constructor(form = undefined) {
      if (form !== undefined) {
        throw typeError("Failed to construct 'FormData': parameter 1 is not of type 'HTMLFormElement'.", 'ERR_INVALID_ARG_TYPE');
      }
      this[kState] = [];
    }
    append(name, value, filename = undefined) {
      if (arguments.length < 2) throw typeError(`Failed to execute 'append' on 'FormData': 2 arguments required, but only ${arguments.length} present.`);
      if (filename !== undefined && !(value instanceof Blob)) {
        throw typeError("Failed to execute 'append' on 'FormData': parameter 2 is not of type 'Blob'");
      }
      this[kState].push(toEntry(name, value, filename));
    }
    delete(name) { this[kState] = this[kState].filter(([n]) => n !== String(name)); }
    get(name) { return this[kState].find(([n]) => n === String(name))?.[1] ?? null; }
    getAll(name) { return this[kState].filter(([n]) => n === String(name)).map(([, v]) => v); }
    has(name) { return this[kState].some(([n]) => n === String(name)); }
    set(name, value, filename = undefined) {
      if (arguments.length < 2) throw typeError(`Failed to execute 'set' on 'FormData': 2 arguments required, but only ${arguments.length} present.`);
      const entry = toEntry(name, value, filename);
      const first = this[kState].findIndex(([n]) => n === entry[0]);
      if (first < 0) { this[kState].push(entry); return; }
      this[kState][first] = entry;
      this[kState] = this[kState].filter(([n], i) => i <= first || n !== entry[0]);
    }
    forEach(callback, thisArg) { for (const [name, value] of this[kState]) callback.call(thisArg, value, name, this); }
    *entries() { yield* this[kState].map(([n, v]) => [n, v]); }
    *keys() { for (const [name] of this[kState]) yield name; }
    *values() { for (const [, value] of this[kState]) yield value; }
    [Symbol.iterator]() { return this.entries(); }
    get [Symbol.toStringTag]() { return 'FormData'; }
  }

  // multipart/form-data, as undici writes it.
  const escapeName = (name) => name.replace(/\n/g, '%0A').replace(/\r/g, '%0D').replace(/"/g, '%22');
  const normalizeNewlines = (value) => value.replace(/\r?\n|\r/g, '\r\n');
  const multipart = (form) => {
    const boundary = `----formdata-undici-0${`${Math.floor(Math.random() * 1e11)}`.padStart(11, '0')}`;
    const parts = [];
    for (const [name, value] of form) {
      if (typeof value === 'string') {
        parts.push(encoder.encode(`--${boundary}\r\nContent-Disposition: form-data; name="${escapeName(normalizeNewlines(name))}"\r\n\r\n${normalizeNewlines(value)}\r\n`));
      } else {
        parts.push(encoder.encode(`--${boundary}\r\nContent-Disposition: form-data; name="${escapeName(normalizeNewlines(name))}"`
          + `; filename="${escapeName(value.name)}"\r\nContent-Type: ${value.type || 'application/octet-stream'}\r\n\r\n`));
        parts.push(blobBytes.get(value), encoder.encode('\r\n'));
      }
    }
    parts.push(encoder.encode(`--${boundary}--\r\n`));
    return { bytes: concat(parts), type: `multipart/form-data; boundary=${boundary}` };
  };

  /** A multipart/form-data or urlencoded body, read back into a FormData (Body.formData()). */
  const parseFormData = (bytes, contentType) => {
    const form = new FormData();
    const type = (contentType ?? '').toLowerCase();
    if (type.startsWith('application/x-www-form-urlencoded')) {
      for (const [name, value] of new URLSearchParams(utf8(bytes))) form.append(name, value);
      return form;
    }
    const boundaryMatch = /boundary=(?:"([^"]+)"|([^;\s]+))/i.exec(contentType ?? '');
    if (!type.startsWith('multipart/form-data') || !boundaryMatch) throw typeError('Content-Type was not one of "multipart/form-data" or "application/x-www-form-urlencoded".');
    const boundary = encoder.encode(`--${boundaryMatch[1] ?? boundaryMatch[2]}`);
    const indexOf = (needle, from) => {
      outer: for (let i = from; i <= bytes.length - needle.length; i++) {
        for (let j = 0; j < needle.length; j++) if (bytes[i + j] !== needle[j]) continue outer;
        return i;
      }
      return -1;
    };
    let at = indexOf(boundary, 0);
    if (at < 0) throw typeError('Failed to parse body as FormData.');
    while (true) {
      at += boundary.length;
      if (bytes[at] === 45 && bytes[at + 1] === 45) break;   // the closing boundary
      at += 2;                                               // CRLF
      const headersEnd = indexOf(encoder.encode('\r\n\r\n'), at);
      if (headersEnd < 0) throw typeError('Failed to parse body as FormData.');
      const head = utf8(bytes.slice(at, headersEnd));
      const next = indexOf(boundary, headersEnd + 4);
      if (next < 0) throw typeError('Failed to parse body as FormData.');
      const content = bytes.slice(headersEnd + 4, next - 2);
      const disposition = /content-disposition:[^\r\n]*/i.exec(head)?.[0] ?? '';
      const name = /\bname="([^"]*)"/i.exec(disposition)?.[1] ?? '';
      const filename = /\bfilename="([^"]*)"/i.exec(disposition)?.[1];
      const partType = /content-type:\s*([^\r\n]*)/i.exec(head)?.[1] ?? '';
      if (filename !== undefined) form.append(name, new File([content], filename, { type: partType }));
      else form.append(name, utf8(content));
      at = next;
    }
    return form;
  };

  // --- bodies ---

  const isReadableStream = (value) => value instanceof ReadableStream;

  /** A body as the Fetch spec extracts it: a stream of it, its bytes if known up front, and its content type. */
  const extractBody = (body, keepalive = false) => {
    if (body === null || body === undefined) return null;
    if (typeof body === 'string') return { bytes: encoder.encode(body), type: 'text/plain;charset=UTF-8' };
    if (body instanceof URLSearchParams) return { bytes: encoder.encode(body.toString()), type: 'application/x-www-form-urlencoded;charset=UTF-8' };
    if (body instanceof Blob) return { bytes: blobBytes.get(body), type: body.type || null };
    if (body instanceof ArrayBuffer || ArrayBuffer.isView(body)) return { bytes: bytesOf(body), type: null };
    if (body instanceof FormData) { const { bytes, type } = multipart(body); return { bytes, type }; }
    if (isReadableStream(body)) {
      if (keepalive) throw typeError('keepalive');
      return { stream: body, type: null };
    }
    if (typeof body[Symbol.asyncIterator] === 'function') {
      const iterator = body[Symbol.asyncIterator]();
      return { stream: new ReadableStream({
        async pull(controller) {
          const { value, done } = await iterator.next();
          if (done) controller.close();
          else controller.enqueue(typeof value === 'string' ? encoder.encode(value) : bytesOf(value));
        },
        async cancel(reason) { await iterator.return?.(reason); },
      }), type: null };
    }
    return { bytes: encoder.encode(String(body)), type: 'text/plain;charset=UTF-8' };
  };

  const streamOfBytes = (bytes) => new ReadableStream({
    type: 'bytes',
    start(controller) { if (bytes.byteLength) controller.enqueue(bytes.slice()); controller.close(); },
  });

  /** Reads a body's stream to its end, once. */
  const consume = async (owner, method) => {
    const state = owner[kState];
    if (state.bodyUsed || state.body?.locked) throw typeError(`Body is unusable: Body has already been read`);
    state.bodyUsed = true;
    if (state.body === null) return new Uint8Array(0);
    const reader = state.body.getReader();
    const chunks = [];
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      chunks.push(value instanceof Uint8Array ? value : bytesOf(value));
    }
    return concat(chunks);
  };

  const bodyMethods = {
    get body() { return this[kState].body; },
    get bodyUsed() { return this[kState].bodyUsed || Boolean(this[kState].body?.locked && this[kState].disturbed); },
    async arrayBuffer() { return (await consume(this)).buffer; },
    async bytes() { return consume(this); },
    async text() { return utf8(await consume(this)); },
    async json() { return JSON.parse(utf8(await consume(this))); },
    async blob() {
      const bytes = await consume(this);
      const blob = new Blob([], { type: this.headers.get('content-type') ?? '' });
      blobBytes.set(blob, bytes);
      return blob;
    },
    async formData() { return parseFormData(await consume(this), this.headers.get('content-type')); },
  };
  const mixBody = (cls) => {
    for (const key of Object.keys(Object.getOwnPropertyDescriptors(bodyMethods))) {
      Object.defineProperty(cls.prototype, key, { ...Object.getOwnPropertyDescriptor(bodyMethods, key), enumerable: true });
    }
  };

  const teeBody = (state) => {
    if (state.body === null) return null;
    const [one, two] = state.body.tee();
    state.body = one;
    return two;
  };

  // --- Response ---

  const NULL_BODY = new Set([101, 103, 204, 205, 304]);
  const REDIRECTS = new Set([301, 302, 303, 307, 308]);

  class Response {
    constructor(body = null, init = {}) {
      if (init !== null && init !== undefined && typeof init !== 'object') {
        throw typeError("Response constructor: Expected init to be a dictionary.");
      }
      const status = init?.status === undefined ? 200 : Number(init.status);
      if (!Number.isInteger(status) || status < 200 || status > 599) {
        throw new RangeError('init["status"] must be in the range of 200 to 599, inclusive.');
      }
      const statusText = init?.statusText === undefined ? '' : String(init.statusText);
      if (/[^\t\x20-\x7e\x80-\xff]/.test(statusText)) throw typeError('Invalid statusText');
      const extracted = extractBody(body);
      if (extracted && NULL_BODY.has(status)) throw typeError(`Response constructor: Invalid response status code ${status}`);
      const headers = new Headers(init?.headers);
      if (extracted?.type && !headers.has('content-type')) headers.append('content-type', extracted.type);
      this[kState] = { type: 'default', url: '', urlList: [], status, statusText, headers, redirected: false, bodyUsed: false,
        body: extracted === null ? null : extracted.stream ?? streamOfBytes(extracted.bytes) };
    }
    static error() {
      const response = new Response(null, { status: 200 });
      Object.assign(response[kState], { type: 'error', status: 0, statusText: '' });
      immutable(response[kState].headers);
      return response;
    }
    static redirect(url, status = 302) {
      const parsed = new URL(String(url));
      if (!REDIRECTS.has(status)) throw new RangeError(`Invalid status code ${status}`);
      const response = new Response(null, { status: 200 });
      response[kState].status = status;
      response[kState].headers.set('location', parsed.href);
      immutable(response[kState].headers);
      return response;
    }
    static json(data, init = {}) {
      const text = JSON.stringify(data);
      if (text === undefined) throw typeError('Value is not JSON serializable');
      const response = new Response(text, init);
      response[kState].headers.set('content-type', 'application/json');
      return response;
    }
    get type() { return this[kState].type; }
    get url() { return this[kState].url; }
    get redirected() { return this[kState].redirected; }
    get status() { return this[kState].status; }
    get ok() { return this[kState].status >= 200 && this[kState].status <= 299; }
    get statusText() { return this[kState].statusText; }
    get headers() { return this[kState].headers; }
    clone() {
      if (this.bodyUsed || this[kState].body?.locked) throw typeError('Response.clone: Body has already been consumed.');
      const copy = new Response(null, { status: 200 });
      Object.assign(copy[kState], { ...this[kState], headers: new Headers(this[kState].headers), body: teeBody(this[kState]) });
      if (this[kState].headers[kState].guard === 'immutable') immutable(copy[kState].headers);
      return copy;
    }
    get [Symbol.toStringTag]() { return 'Response'; }
  }
  mixBody(Response);

  // --- Request ---

  // The Fetch spec normalizes these six only; undici warns once about a lowercase patch, which servers refuse.
  const METHODS = new Map(['DELETE', 'GET', 'HEAD', 'OPTIONS', 'POST', 'PUT'].map((m) => [m.toLowerCase(), m]));
  let patchWarned = false;

  class Request {
    constructor(input, init = {}) {
      if (arguments.length < 1) throw typeError("Failed to construct 'Request': 1 argument required, but only 0 present.");
      let base = null;
      let url;
      if (input instanceof Request) {
        base = input[kState];
        url = base.url;
      } else {
        try {
          url = new URL(String(input)).href;
        } catch (cause) {
          throw Object.assign(typeError(`Failed to parse URL from ${String(input)}`), { cause });
        }
        if (/^[^:]+:\/\/[^/]*@/.test(url)) {
          throw typeError(`Request cannot be constructed from a URL that includes credentials: ${String(input)}`);
        }
      }
      let method = init?.method !== undefined ? String(init.method) : base?.method ?? 'GET';
      if (!TOKEN.test(method)) throw typeError(`'${method}' is not a valid HTTP method.`);
      if (['connect', 'trace', 'track'].includes(method.toLowerCase())) throw typeError(`'${method}' HTTP method is unsupported.`);
      method = METHODS.get(method.toLowerCase()) ?? method;
      if (method === 'patch' && !patchWarned) {
        patchWarned = true;
        globalThis[Symbol.for('cluster93.process')]?.emitWarning('Using `patch` is highly likely to result in a `405 Method Not Allowed`. `PATCH` is much more likely to succeed.',
          { code: 'UNDICI-FETCH-patch' });
      }
      const headers = new Headers(init?.headers ?? base?.headers);
      const hasBody = init?.body !== undefined && init?.body !== null;
      if (hasBody && (method === 'GET' || method === 'HEAD')) throw typeError('Request with GET/HEAD method cannot have body.');
      let body = null;
      if (hasBody) {
        const extracted = extractBody(init.body, init.keepalive);
        if (extracted.stream && init.duplex !== 'half') throw typeError('RequestInit: duplex option is required when sending a body.');
        if (extracted.type && !headers.has('content-type')) headers.append('content-type', extracted.type);
        body = extracted.stream ?? streamOfBytes(extracted.bytes);
      } else if (base && base.body) {
        if (base.bodyUsed || base.body.locked) throw typeError('Cannot construct a Request with a Request object that has already been used.');
        body = base.body;
        base.bodyUsed = true;
      }
      const redirect = init?.redirect ?? base?.redirect ?? 'follow';
      if (!['follow', 'error', 'manual'].includes(redirect)) {
        throw typeError(`Failed to construct 'Request': The provided value '${redirect}' is not a valid enum value of type RequestRedirect.`);
      }
      const signal = init?.signal ?? base?.signal ?? null;
      const controller = new AbortController();
      if (signal) {
        if (signal.aborted) controller.abort(signal.reason);
        else signal.addEventListener('abort', () => controller.abort(signal.reason), { once: true });
      }
      this[kState] = { method, url, headers, body, bodyUsed: false, redirect, signal: controller.signal,
        referrer: init?.referrer === '' ? '' : 'about:client', referrerPolicy: init?.referrerPolicy ?? base?.referrerPolicy ?? '',
        mode: init?.mode ?? base?.mode ?? 'cors', credentials: init?.credentials ?? base?.credentials ?? 'same-origin',
        cache: init?.cache ?? base?.cache ?? 'default', integrity: init?.integrity ?? base?.integrity ?? '',
        keepalive: Boolean(init?.keepalive ?? base?.keepalive ?? false), duplex: 'half' };
    }
    get method() { return this[kState].method; }
    get url() { return this[kState].url; }
    get headers() { return this[kState].headers; }
    get destination() { return ''; }
    get referrer() { return this[kState].referrer; }
    get referrerPolicy() { return this[kState].referrerPolicy; }
    get mode() { return this[kState].mode; }
    get credentials() { return this[kState].credentials; }
    get cache() { return this[kState].cache; }
    get redirect() { return this[kState].redirect; }
    get integrity() { return this[kState].integrity; }
    get keepalive() { return this[kState].keepalive; }
    get isReloadNavigation() { return false; }
    get isHistoryNavigation() { return false; }
    get signal() { return this[kState].signal; }
    get duplex() { return this[kState].duplex; }
    clone() {
      if (this.bodyUsed || this[kState].body?.locked) throw typeError('Request.clone: Body has already been consumed.');
      const copy = new Request(this.url, { method: this.method, headers: this.headers, redirect: this.redirect, signal: this.signal });
      copy[kState].body = teeBody(this[kState]);
      return copy;
    }
    get [Symbol.toStringTag]() { return 'Request'; }
  }
  mixBody(Request);

  // --- fetch ---

  const fetchFailed = (cause) => Object.assign(typeError('fetch failed'), { cause });
  const abortError = (signal) => signal.reason ?? Object.assign(new DOMException('This operation was aborted', 'AbortError'));

  /** One hop: the host's streamed request, through the egress gate. */
  const hop = async (request, url, method, headers, bodyBytes, bodyStream, signal) => {
    const plain = {};
    for (const [name, value] of headers[kState].list) {
      const key = name.toLowerCase();
      plain[key] = plain[key] === undefined ? value : [].concat(plain[key], value);
    }
    if (!('accept' in plain)) plain.accept = '*/*';
    if (!('accept-language' in plain)) plain['accept-language'] = '*';
    if (!('sec-fetch-mode' in plain)) plain['sec-fetch-mode'] = 'cors';
    if (!('user-agent' in plain)) plain['user-agent'] = 'node';
    if (!('accept-encoding' in plain)) plain['accept-encoding'] = 'gzip, deflate';
    if (bodyBytes) plain['content-length'] = String(bodyBytes.byteLength);
    const { id } = await httpHost.open({ url, method, headers: plain, chunked: Boolean(bodyStream) });
    // Asked for now, as http.js does: a quick answer settles before the body ends, and the request then leaves the registry.
    const response = httpHost.response(id);
    response.catch(() => { });
    const onAbort = () => httpHost.abort(id);
    signal.addEventListener('abort', onAbort, { once: true });
    try {
      if (bodyBytes && bodyBytes.byteLength) await httpHost.write(id, bodyBytes);
      if (bodyStream) {
        const reader = bodyStream.getReader();
        while (true) {
          const { value, done } = await reader.read();
          if (done) break;
          await httpHost.write(id, value instanceof Uint8Array ? value : bytesOf(value));
        }
      }
      await httpHost.end(id);
      return await response;
    } finally {
      signal.removeEventListener('abort', onAbort);
    }
  };

  /** The host's body stream as a ReadableStream, read as the script reads. */
  const responseBody = (body, signal) => {
    const iterator = body[Symbol.asyncIterator]();
    return new ReadableStream({
      async pull(controller) {
        const { value, done } = await iterator.next();
        if (done) controller.close();
        else controller.enqueue(new Uint8Array(value.buffer, value.byteOffset, value.byteLength));
      },
      cancel() { body.cancel(); },
    });
  };

  // Content-encoding gzip/deflate is undone, as undici does, so bodies read as they were sent.
  const decoded = (stream, encoding) => {
    const kind = String(encoding ?? '').trim().toLowerCase();
    const format = kind === 'gzip' || kind === 'x-gzip' ? 'gzip' : kind === 'deflate' ? 'deflate' : kind === 'br' ? 'brotli' : null;
    if (!format || typeof globalThis.DecompressionStream !== 'function') return stream;
    return stream.pipeThrough(new globalThis.DecompressionStream(format === 'brotli' ? 'br' : format));
  };

  async function fetch(input, init = undefined) {
    const request = new Request(input, init);
    const state = request[kState];
    if (state.signal.aborted) throw abortError(state.signal);
    let url = state.url;
    let method = state.method;
    let headers = new Headers(state.headers);
    let bodyBytes = null;
    let bodyStream = null;
    if (state.body) {
      // A body given up front is read now, so a redirect can send it again; a stream goes as it comes.
      if (init?.body !== undefined && extractBody(init.body)?.bytes) bodyBytes = extractBody(init.body).bytes;
      else bodyStream = state.body;
      state.bodyUsed = true;
    }
    const urlList = [url];
    for (let redirects = 0; ; redirects++) {
      let answer;
      try {
        answer = await hop(request, url, method, headers, bodyBytes, bodyStream, state.signal);
      } catch (cause) {
        if (state.signal.aborted) throw abortError(state.signal);
        throw fetchFailed(cause);
      }
      const { meta, body } = answer;
      const status = meta.status;
      const location = (() => {
        for (let i = 0; i + 1 < (meta.rawHeaders ?? []).length; i += 2) if (String(meta.rawHeaders[i]).toLowerCase() === 'location') return meta.rawHeaders[i + 1];
        return null;
      })();
      if (REDIRECTS.has(status) && location !== null && state.redirect !== 'manual') {
        body.cancel();
        if (state.redirect === 'error') throw fetchFailed(new Error('unexpected redirect'));
        if (redirects >= 20) throw fetchFailed(new Error('redirect count exceeded'));
        if (bodyStream && status !== 303) throw fetchFailed(new Error('Cannot follow redirect with a stream body'));
        const next = new URL(location, url);
        if (next.protocol !== 'http:' && next.protocol !== 'https:') throw fetchFailed(new Error('URL scheme must be a HTTP(S) scheme'));
        if (((status === 301 || status === 302) && method === 'POST') || (status === 303 && method !== 'GET' && method !== 'HEAD')) {
          method = 'GET';
          bodyBytes = null;
          bodyStream = null;
          for (const name of ['content-encoding', 'content-language', 'content-location', 'content-type', 'content-length']) headers.delete(name);
        }
        if (new URL(url).origin !== next.origin) {
          for (const name of ['authorization', 'proxy-authorization', 'cookie', 'host']) headers.delete(name);
        }
        url = next.href;
        urlList.push(url);
        continue;
      }
      const responseHeaders = new Headers();
      for (let i = 0; i + 1 < (meta.rawHeaders ?? []).length; i += 2) responseHeaders.append(meta.rawHeaders[i], meta.rawHeaders[i + 1]);
      const nullBody = NULL_BODY.has(status) || method === 'HEAD';
      const response = new Response(null, { status: 200 });
      Object.assign(response[kState], {
        type: 'basic', url, urlList, status, statusText: meta.statusText ?? '', headers: immutable(responseHeaders), redirected: urlList.length > 1,
        body: nullBody ? (body.cancel(), null) : decoded(responseBody(body, state.signal), responseHeaders.get('content-encoding')),
      });
      return response;
    }
  }

  return { Headers, Request, Response, FormData, fetch };
})()
