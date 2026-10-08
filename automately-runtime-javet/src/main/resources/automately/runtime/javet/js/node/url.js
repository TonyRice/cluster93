// A stand-in for Node's `url` (D-089). V8 has no URL, so this is a compact URL and URLSearchParams following the WHATWG
// rules for the common cases: special schemes (http, https, ws, wss, ftp, file) with an authority, other schemes with
// opaque paths, relative references, dot segments, default ports and percent-encoding. Hostnames are lowercased but
// not converted to punycode, and IPv6 addresses aren't normalised. Also fileURLToPath, pathToFileURL and the legacy
// parse, format and resolve.

const SPECIAL = { 'http:': '80', 'https:': '443', 'ws:': '80', 'wss:': '443', 'ftp:': '21', 'file:': '' };

// Percent-encode what the URL standard's encode sets would, keeping existing escapes.
function encodeSet(s, keep) {
  let out = '';
  for (const ch of s) {
    const c = ch.codePointAt(0);
    if (c > 0x20 && c < 0x7f && !keep.includes(ch)) {
      out += ch;
    } else if (ch === '%') {
      out += ch;
    } else {
      for (const b of new TextEncoder().encode(ch)) {
        out += '%' + b.toString(16).toUpperCase().padStart(2, '0');
      }
    }
  }
  return out;
}
const PATH_ESCAPE = ' "#<>?`{}';
const QUERY_ESCAPE = ' "#<>';
const FRAGMENT_ESCAPE = ' "<>`';
const USERINFO_ESCAPE = ' "#<>?`{}/:;=@[\\]^|';

function removeDotSegments(path) {
  const out = [];
  const segments = path.split('/');
  for (let i = 0; i < segments.length; i++) {
    const s = segments[i];
    const last = i === segments.length - 1;
    if (s === '..' || s.toLowerCase() === '%2e%2e' || s.toLowerCase() === '.%2e' || s.toLowerCase() === '%2e.') {
      if (out.length > 1) {
        out.pop();
      }
      if (last) {
        out.push('');
      }
    } else if (s === '.' || s.toLowerCase() === '%2e') {
      if (last) {
        out.push('');
      }
    } else {
      out.push(s);
    }
  }
  const joined = out.join('/');
  return joined.startsWith('/') ? joined : '/' + joined;
}

function parseAbsolute(input) {
  const m = /^([a-zA-Z][a-zA-Z0-9+.-]*):(.*)$/s.exec(input);
  if (!m) {
    return null;
  }
  const protocol = m[1].toLowerCase() + ':';
  let rest = m[2];
  const parts = { protocol, username: '', password: '', hostname: '', port: '', pathname: '', search: '', hash: '', opaque: false };
  const hashAt = rest.indexOf('#');
  if (hashAt >= 0) {
    parts.hash = rest.slice(hashAt);
    rest = rest.slice(0, hashAt);
  }
  const queryAt = rest.indexOf('?');
  if (queryAt >= 0) {
    parts.search = rest.slice(queryAt);
    rest = rest.slice(0, queryAt);
  }
  const special = protocol in SPECIAL;
  if (special) {
    rest = rest.replace(/\\/g, '/');
  }
  if (special || rest.startsWith('//')) {
    let authority = '';
    if (protocol === 'file:') {
      // file:///path has an empty host; file://host/path names one.
      if (rest.startsWith('//')) {
        rest = rest.slice(2);
        const slash = rest.indexOf('/');
        authority = slash < 0 ? rest : rest.slice(0, slash);
        rest = slash < 0 ? '' : rest.slice(slash);
      }
    } else {
      rest = special ? rest.replace(/^\/*/, '') : rest.slice(2);
      const slash = rest.indexOf('/');
      authority = slash < 0 ? rest : rest.slice(0, slash);
      rest = slash < 0 ? '' : rest.slice(slash);
    }
    const at = authority.lastIndexOf('@');
    let host = authority;
    if (at >= 0) {
      const userinfo = authority.slice(0, at);
      host = authority.slice(at + 1);
      const colon = userinfo.indexOf(':');
      parts.username = encodeSet(colon < 0 ? userinfo : userinfo.slice(0, colon), USERINFO_ESCAPE);
      parts.password = colon < 0 ? '' : encodeSet(userinfo.slice(colon + 1), USERINFO_ESCAPE);
    }
    const portMatch = /^(\[[^\]]*\]|[^:]*)(?::(\d*))?$/.exec(host);
    if (!portMatch) {
      throw new TypeError('Invalid URL: ' + input);
    }
    parts.hostname = portMatch[1].toLowerCase();
    if (/[\s<>^|%]/.test(parts.hostname) || (special && protocol !== 'file:' && parts.hostname === '')) {
      throw new TypeError('Invalid URL: ' + input);
    }
    const port = portMatch[2] || '';
    if (port !== '' && Number(port) > 65535) {
      throw new TypeError('Invalid URL: ' + input);
    }
    parts.port = port === '' || String(Number(port)) === SPECIAL[protocol] ? '' : String(Number(port));
    parts.pathname = removeDotSegments(encodeSet(rest || '/', PATH_ESCAPE));
  } else {
    parts.opaque = !rest.startsWith('/');
    parts.pathname = parts.opaque ? encodeSet(rest, ' "<>`').replace(/%20/g, ' ') : removeDotSegments(encodeSet(rest, PATH_ESCAPE));
  }
  parts.search = parts.search === '?' ? '' : parts.search && '?' + encodeSet(parts.search.slice(1), QUERY_ESCAPE);
  parts.hash = parts.hash === '#' ? '' : parts.hash && '#' + encodeSet(parts.hash.slice(1), FRAGMENT_ESCAPE);
  return parts;
}

function resolveRelative(input, base) {
  if (base.opaque) {
    if (input.startsWith('#')) {
      return { ...base, hash: input === '#' ? '' : '#' + encodeSet(input.slice(1), FRAGMENT_ESCAPE) };
    }
    throw new TypeError('Invalid URL: ' + input);
  }
  const special = base.protocol in SPECIAL;
  if (special) {
    input = input.replace(/\\/g, '/');
  }
  if (input.startsWith('//')) {
    return parseAbsolute(base.protocol + input);
  }
  let hash = '';
  const hashAt = input.indexOf('#');
  if (hashAt >= 0) {
    hash = input.slice(hashAt);
    input = input.slice(0, hashAt);
  }
  let search = null;
  const queryAt = input.indexOf('?');
  if (queryAt >= 0) {
    search = input.slice(queryAt);
    input = input.slice(0, queryAt);
  }
  let pathname = base.pathname;
  if (input.startsWith('/')) {
    pathname = input;
  } else if (input !== '') {
    pathname = base.pathname.slice(0, base.pathname.lastIndexOf('/') + 1) + input;
  }
  const merged = { ...base, pathname: removeDotSegments(encodeSet(pathname, PATH_ESCAPE)) };
  merged.search = search === null ? (input === '' ? base.search : '') : search === '?' ? '' : '?' + encodeSet(search.slice(1), QUERY_ESCAPE);
  merged.hash = hash === '' || hash === '#' ? '' : '#' + encodeSet(hash.slice(1), FRAGMENT_ESCAPE);
  return merged;
}

function formEncode(s) {
  return encodeURIComponent(s).replace(/%20/g, '+').replace(/[!'()~]/g, (c) => '%' + c.charCodeAt(0).toString(16).toUpperCase());
}

function formDecode(s) {
  try {
    return decodeURIComponent(s.replace(/\+/g, ' '));
  } catch {
    return s.replace(/\+/g, ' ');
  }
}

export class URLSearchParams {
  #list = [];
  #url = null;

  constructor(init = '') {
    if (typeof init === 'string') {
      this.#parse(init);
    } else if (init instanceof URLSearchParams) {
      this.#list = init.#list.map(([k, v]) => [k, v]);
    } else if (init && typeof init[Symbol.iterator] === 'function') {
      for (const pair of init) {
        const [k, v] = Array.from(pair);
        this.#list.push([String(k), String(v)]);
      }
    } else if (init && typeof init === 'object') {
      for (const k of Object.keys(init)) {
        this.#list.push([k, String(init[k])]);
      }
    }
  }

  #parse(s) {
    this.#list = [];
    for (const piece of (s.startsWith('?') ? s.slice(1) : s).split('&')) {
      if (piece === '') {
        continue;
      }
      const eq = piece.indexOf('=');
      this.#list.push(eq < 0 ? [formDecode(piece), ''] : [formDecode(piece.slice(0, eq)), formDecode(piece.slice(eq + 1))]);
    }
  }

  #update() {
    if (this.#url) {
      const query = this.toString();
      this.#url._setSearch(query === '' ? '' : '?' + query);
    }
  }

  static _attach(params, url, search) {
    params.#url = url;
    params.#parse(search);
  }

  get size() { return this.#list.length; }
  append(name, value) { this.#list.push([String(name), String(value)]); this.#update(); }
  delete(name, value) {
    this.#list = this.#list.filter(([k, v]) => !(k === String(name) && (value === undefined || v === String(value))));
    this.#update();
  }
  get(name) { const found = this.#list.find(([k]) => k === String(name)); return found ? found[1] : null; }
  getAll(name) { return this.#list.filter(([k]) => k === String(name)).map(([, v]) => v); }
  has(name, value) { return this.#list.some(([k, v]) => k === String(name) && (value === undefined || v === String(value))); }
  set(name, value) {
    const key = String(name);
    const at = this.#list.findIndex(([k]) => k === key);
    if (at < 0) {
      this.#list.push([key, String(value)]);
    } else {
      this.#list[at] = [key, String(value)];
      this.#list = this.#list.filter(([k], i) => k !== key || i === at);
    }
    this.#update();
  }
  sort() {
    this.#list = this.#list.map((e, i) => [e, i]).sort(([a, i], [b, j]) => (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : i - j)).map(([e]) => e);
    this.#update();
  }
  forEach(fn, thisArg) { for (const [k, v] of this.#list) { fn.call(thisArg, v, k, this); } }
  *keys() { for (const [k] of this.#list) { yield k; } }
  *values() { for (const [, v] of this.#list) { yield v; } }
  *entries() { for (const [k, v] of this.#list) { yield [k, v]; } }
  [Symbol.iterator]() { return this.entries(); }
  toString() { return this.#list.map(([k, v]) => formEncode(k) + '=' + formEncode(v)).join('&'); }
  get [Symbol.toStringTag]() { return 'URLSearchParams'; }
}

export class URL {
  #p;
  #params = new URLSearchParams();

  constructor(input, base) {
    const text = String(input).trim();
    let parsed = parseAbsolute(text);
    if (!parsed) {
      if (base === undefined) {
        throw new TypeError('Invalid URL: ' + text);
      }
      const b = base instanceof URL ? base.#p : new URL(base).#p;
      parsed = resolveRelative(text, b);
    }
    this.#p = parsed;
    URLSearchParams._attach(this.#params, this, parsed.search);
  }

  static canParse(input, base) {
    try {
      new URL(input, base);
      return true;
    } catch {
      return false;
    }
  }

  _setSearch(search) {
    this.#p.search = search;
  }

  #authority() {
    const p = this.#p;
    if (!(p.protocol in SPECIAL) && p.hostname === '' && p.opaque) {
      return null;
    }
    const user = p.username || p.password ? p.username + (p.password ? ':' + p.password : '') + '@' : '';
    return user + this.host;
  }

  get href() {
    const p = this.#p;
    const authority = this.#authority();
    const hierarchical = authority !== null && (p.protocol in SPECIAL || p.hostname !== '' || !p.opaque);
    return p.protocol + (hierarchical && (p.protocol in SPECIAL || p.hostname !== '') ? '//' + authority : '') + p.pathname + p.search + p.hash;
  }
  set href(value) {
    const next = new URL(value);
    this.#p = next.#p;
    URLSearchParams._attach(this.#params, this, this.#p.search);
  }
  get origin() {
    const p = this.#p;
    return p.protocol in SPECIAL && p.protocol !== 'file:' ? p.protocol + '//' + this.host : 'null';
  }
  get protocol() { return this.#p.protocol; }
  set protocol(value) {
    const v = String(value).replace(/:.*$/, '').toLowerCase() + ':';
    if (/^[a-z][a-z0-9+.-]*:$/.test(v) && (v in SPECIAL) === (this.#p.protocol in SPECIAL)) {
      this.#p.protocol = v;
      if (this.#p.port === SPECIAL[v]) {
        this.#p.port = '';
      }
    }
  }
  get username() { return this.#p.username; }
  set username(value) { this.#p.username = encodeSet(String(value), USERINFO_ESCAPE); }
  get password() { return this.#p.password; }
  set password(value) { this.#p.password = encodeSet(String(value), USERINFO_ESCAPE); }
  get host() { return this.#p.hostname + (this.#p.port ? ':' + this.#p.port : ''); }
  set host(value) {
    const m = /^(\[[^\]]*\]|[^:/?#]*)(?::(\d*))?/.exec(String(value));
    if (m && m[1]) {
      this.#p.hostname = m[1].toLowerCase();
      if (m[2] !== undefined) {
        this.port = m[2];
      }
    }
  }
  get hostname() { return this.#p.hostname; }
  set hostname(value) {
    const m = /^(\[[^\]]*\]|[^:/?#]*)/.exec(String(value));
    if (m && m[1]) {
      this.#p.hostname = m[1].toLowerCase();
    }
  }
  get port() { return this.#p.port; }
  set port(value) {
    const v = String(value);
    if (v === '') {
      this.#p.port = '';
      return;
    }
    const m = /^\d+/.exec(v);
    if (m && Number(m[0]) <= 65535) {
      this.#p.port = String(Number(m[0])) === SPECIAL[this.#p.protocol] ? '' : String(Number(m[0]));
    }
  }
  get pathname() { return this.#p.pathname; }
  set pathname(value) {
    if (this.#p.opaque) {
      return;
    }
    const v = String(value);
    this.#p.pathname = removeDotSegments(encodeSet(v.startsWith('/') ? v : '/' + v, PATH_ESCAPE));
  }
  get search() { return this.#p.search; }
  set search(value) {
    const v = String(value).replace(/^\?/, '');
    this.#p.search = v === '' ? '' : '?' + encodeSet(v, QUERY_ESCAPE);
    URLSearchParams._attach(this.#params, this, this.#p.search);
  }
  get searchParams() { return this.#params; }
  get hash() { return this.#p.hash; }
  set hash(value) {
    const v = String(value).replace(/^#/, '');
    this.#p.hash = v === '' ? '' : '#' + encodeSet(v, FRAGMENT_ESCAPE);
  }
  toString() { return this.href; }
  toJSON() { return this.href; }
  get [Symbol.toStringTag]() { return 'URL'; }
}

export function fileURLToPath(url) {
  const u = typeof url === 'string' ? new URL(url) : url;
  if (!(u instanceof URL) || u.protocol !== 'file:') {
    throw new TypeError('The URL must be of scheme file');
  }
  if (u.hostname !== '' && u.hostname !== 'localhost') {
    throw new TypeError('File URL host must be "localhost" or empty');
  }
  if (/%2f/i.test(u.pathname)) {
    throw new TypeError('File URL path must not include encoded / characters');
  }
  return decodeURIComponent(u.pathname);
}

export function pathToFileURL(path) {
  let p = String(path);
  if (!p.startsWith('/')) {
    p = '/' + p;
  }
  const url = new URL('file:///');
  url.pathname = p.replace(/%/g, '%25').replace(/\\/g, '%5C').replace(/\n/g, '%0A').replace(/\r/g, '%0D').replace(/\t/g, '%09');
  return url;
}

export function urlToHttpOptions(url) {
  return {
    protocol: url.protocol,
    hostname: url.hostname.startsWith('[') ? url.hostname.slice(1, -1) : url.hostname,
    hash: url.hash,
    search: url.search,
    pathname: url.pathname,
    path: url.pathname + url.search,
    href: url.href,
    ...(url.port !== '' ? { port: Number(url.port) } : {}),
    ...(url.username || url.password ? { auth: decodeURIComponent(url.username) + ':' + decodeURIComponent(url.password) } : {}),
  };
}

// --- the legacy API ---

export function parse(urlString, parseQueryString = false) {
  const s = String(urlString);
  const result = { protocol: null, slashes: null, auth: null, host: null, port: null, hostname: null, hash: null, search: null,
    query: null, pathname: null, path: null, href: s };
  let rest = s;
  const hashAt = rest.indexOf('#');
  if (hashAt >= 0) {
    result.hash = rest.slice(hashAt);
    rest = rest.slice(0, hashAt);
  }
  const queryAt = rest.indexOf('?');
  if (queryAt >= 0) {
    result.search = rest.slice(queryAt);
    result.query = rest.slice(queryAt + 1);
    rest = rest.slice(0, queryAt);
  }
  const proto = /^([a-zA-Z][a-zA-Z0-9+.-]*:)/.exec(rest);
  if (proto) {
    result.protocol = proto[1].toLowerCase();
    rest = rest.slice(proto[1].length);
  }
  // Like Node: a host follows // or, for schemes that don't use slashes (mailto:), the colon.
  const slashed = rest.startsWith('//');
  if (slashed || (result.protocol && !(result.protocol in SPECIAL) && !['javascript:', 'gopher:'].includes(result.protocol))) {
    if (slashed) {
      result.slashes = true;
      rest = rest.slice(2);
    }
    const slash = rest.search(/\//);
    const authority = slash < 0 ? rest : rest.slice(0, slash);
    rest = slash < 0 ? '' : rest.slice(slash);
    const at = authority.lastIndexOf('@');
    if (at >= 0) {
      result.auth = decodeURIComponent(authority.slice(0, at));
    }
    result.host = (at >= 0 ? authority.slice(at + 1) : authority).toLowerCase();
    const m = /^(.*?)(?::(\d+))?$/.exec(result.host);
    result.hostname = m[1];
    result.port = m[2] || null;
    if (rest === '' && result.protocol in SPECIAL) {
      rest = '/';
    }
  }
  result.pathname = rest === '' ? null : rest;
  result.path = (result.pathname || '') + (result.search || '') || null;
  if (parseQueryString) {
    const query = {};
    for (const [k, v] of new URLSearchParams(result.query || '')) {
      query[k] = k in query ? [].concat(query[k], v) : v;
    }
    result.query = query;
  }
  result.href = format(result);
  return result;
}

export function format(urlObject) {
  if (urlObject instanceof URL) {
    return urlObject.href;
  }
  if (typeof urlObject === 'string') {
    return format(parse(urlObject));
  }
  const o = urlObject || {};
  let protocol = o.protocol || '';
  if (protocol && !protocol.endsWith(':')) {
    protocol += ':';
  }
  let host = '';
  if (o.host) {
    host = o.host;
  } else if (o.hostname) {
    host = o.hostname + (o.port ? ':' + o.port : '');
  }
  const auth = o.auth ? encodeURIComponent(o.auth).replace(/%3A/i, ':') + '@' : '';
  const slashes = o.slashes || !protocol || protocol in SPECIAL ? '//' : '';
  let search = o.search || '';
  if (!search && o.query && typeof o.query === 'object') {
    // Encoded as Node's querystring does: %20 for spaces, not +.
    const pairs = [];
    for (const [k, v] of Object.entries(o.query)) {
      for (const each of [].concat(v)) {
        pairs.push(encodeURIComponent(k) + '=' + encodeURIComponent(each));
      }
    }
    search = pairs.length ? '?' + pairs.join('&') : '';
  } else if (search && !search.startsWith('?')) {
    search = '?' + search;
  }
  let hash = o.hash || '';
  if (hash && !hash.startsWith('#')) {
    hash = '#' + hash;
  }
  let pathname = o.pathname || '';
  if (host && pathname && !pathname.startsWith('/')) {
    pathname = '/' + pathname;
  }
  return protocol + (host || slashes && protocol === 'file:' ? slashes + auth + host : '') + pathname + search + hash;
}

export function resolve(from, to) {
  const base = new URL(from, 'resolve://x/');
  const resolved = new URL(to, base);
  if (resolved.protocol === 'resolve:') {
    const { pathname, search, hash } = resolved;
    return (from.startsWith('/') ? pathname : pathname.replace(/^\//, '')) + search + hash;
  }
  return resolved.href;
}

export default { URL, URLSearchParams, fileURLToPath, pathToFileURL, urlToHttpOptions, parse, format, resolve };
