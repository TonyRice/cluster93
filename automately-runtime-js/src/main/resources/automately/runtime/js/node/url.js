// A stand-in for Node's `url` (D-089). The URL and URLSearchParams are the globals (D-106), which follow the WHATWG
// rules for the common cases: special schemes (http, https, ws, wss, ftp, file) with an authority, other schemes with
// opaque paths, relative references, dot segments, default ports and percent-encoding. Hostnames are lowercased but
// not converted to punycode, and IPv6 addresses aren't normalised. Also fileURLToPath, pathToFileURL and the legacy
// parse, format and resolve.

// URL and URLSearchParams are the globals (js/url.js, D-106).
const { URL, URLSearchParams } = globalThis;
export { URL, URLSearchParams };

const SPECIAL = { 'http:': '80', 'https:': '443', 'ws:': '80', 'wss:': '443', 'ftp:': '21', 'file:': '' };

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
