// Node's `dns` (D-122), on the host's resolver: the one the egress gate uses, so a script sees the network as it can
// reach it. lookup asks the operating system, as Node's getaddrinfo does; the resolve* functions and reverse ask DNS
// servers, as c-ares does: the cluster's, or ones the script set, which the gate checks like any connection (a public
// resolver is fine, a private one needs a rule). Answers show only what the tenant could reach (network-reach.md §8.2):
// internal names answer ENOTFOUND, addresses it couldn't connect to are left out, and so are records naming internal
// hosts. Arguments, answers and errors are Node 26's.
import { nextTick } from 'node:process';
import { isIP } from 'node:net';

const internals = globalThis[Symbol.for('cluster93.dns')];
const reportUncaught = globalThis[Symbol.for('cluster93.uncaught')];

const codes = {
  NODATA: 'ENODATA', FORMERR: 'EFORMERR', SERVFAIL: 'ESERVFAIL', NOTFOUND: 'ENOTFOUND', NOTIMP: 'ENOTIMP', REFUSED: 'EREFUSED',
  BADQUERY: 'EBADQUERY', BADNAME: 'EBADNAME', BADFAMILY: 'EBADFAMILY', BADRESP: 'EBADRESP', CONNREFUSED: 'ECONNREFUSED',
  TIMEOUT: 'ETIMEOUT', EOF: 'EOF', FILE: 'EFILE', NOMEM: 'ENOMEM', DESTRUCTION: 'EDESTRUCTION', BADSTR: 'EBADSTR',
  BADFLAGS: 'EBADFLAGS', NONAME: 'ENONAME', BADHINTS: 'EBADHINTS', NOTINITIALIZED: 'ENOTINITIALIZED',
  LOADIPHLPAPI: 'ELOADIPHLPAPI', ADDRGETNETWORKPARAMS: 'EADDRGETNETWORKPARAMS', CANCELLED: 'ECANCELLED',
};
export const {
  NODATA, FORMERR, SERVFAIL, NOTFOUND, NOTIMP, REFUSED, BADQUERY, BADNAME, BADFAMILY, BADRESP, CONNREFUSED, TIMEOUT, EOF,
  FILE, NOMEM, DESTRUCTION, BADSTR, BADFLAGS, NONAME, BADHINTS, NOTINITIALIZED, LOADIPHLPAPI, ADDRGETNETWORKPARAMS, CANCELLED,
} = codes;
// getaddrinfo's hints, with Linux's values as Node has them.
export const ADDRCONFIG = 32;
export const ALL = 16;
export const V4MAPPED = 8;

// --- Node's errors ---

const describe = (value) => {
  if (value == null) return String(value);
  if (typeof value === 'function') return `function ${value.name}`;
  if (typeof value === 'object') return value.constructor?.name ? `an instance of ${value.constructor.name}` : 'an object';
  const shown = typeof value === 'string' ? `'${value.length > 25 ? value.slice(0, 25) + '...' : value}'` : String(value);
  return `type ${typeof value} (${shown})`;
};
const typeError = (code, message) => Object.assign(new TypeError(message), { code });
const invalidType = (name, type, value) => typeError('ERR_INVALID_ARG_TYPE',
  `The "${name}" ${name.includes('.') ? 'property' : 'argument'} must be ${type}. Received ${describe(value)}`);
const invalidValue = (name, value, reason = 'is invalid') => typeError('ERR_INVALID_ARG_VALUE',
  `The ${name.includes('.') ? 'property' : 'argument'} '${name}' ${reason}. Received ${typeof value === 'string' ? `'${value}'` : String(value)}`);
const validateString = (value, name) => { if (typeof value !== 'string') throw invalidType(name, 'of type string', value); };
const validateFunction = (value, name) => { if (typeof value !== 'function') throw invalidType(name, 'of type function', value); };
const validateBoolean = (value, name) => { if (typeof value !== 'boolean') throw invalidType(name, 'of type boolean', value); };
const validateOneOf = (value, name, allowed) => {
  if (!allowed.includes(value)) {
    throw invalidValue(name, value, `must be one of: ${allowed.map((v) => (typeof v === 'string' ? `'${v}'` : v)).join(', ')}`);
  }
};

// getaddrinfo's errno values for the codes a lookup can fail with, as libuv numbers them.
const GAI_ERRNO = { ENOTFOUND: -3008, EAI_AGAIN: -3001, EAI_FAIL: -3002, EAI_MEMORY: -3006 };

/** Node's DNSException: "<syscall> <code> <hostname>", with errno, code, syscall and hostname. */
class DNSException extends Error {
  constructor(code, syscall, hostname, errno) {
    super(`${syscall} ${code}${hostname ? ` ${hostname}` : ''}`);
    this.errno = errno;
    this.code = code;
    this.syscall = syscall;
    if (hostname) this.hostname = hostname;
  }
}

/** A host failure as Node's error for `syscall`; a server the gate refused says why. */
function dnsError(err, syscall, hostname) {
  const code = typeof err?.code === 'string' && /^E[A-Z_]+$/.test(err.code) ? err.code : 'ESERVFAIL';
  const error = new DNSException(code, syscall, hostname, syscall === 'getaddrinfo' ? GAI_ERRNO[code] : undefined);
  if (code === 'ECONNREFUSED' && /not allowed/.test(err?.message ?? '')) error.message += ` (${err.message})`;
  return error;
}

const later = (callback, ...args) => nextTick(() => {
  try { callback(...args); } catch (e) { reportUncaught(e, 'a dns callback'); }
});

// --- lookup, as getaddrinfo ---

let defaultOrder = 'verbatim';
export function getDefaultResultOrder() { return defaultOrder; }
export function setDefaultResultOrder(value) {
  validateOneOf(value, 'dnsOrder', ['verbatim', 'ipv4first', 'ipv6first']);
  defaultOrder = value;
}

/** lookup's options as Node reads them: { family, hints, all, order }. */
function lookupOptions(options) {
  const out = { family: 0, hints: 0, all: false, order: defaultOrder };
  if (typeof options === 'number') {
    validateOneOf(options, 'family', [0, 4, 6]);
    out.family = options;
    return out;
  }
  if (options == null) return out;
  if (typeof options !== 'object') throw invalidType('options', 'one of type integer or an instance of Object', options);
  if (options.hints != null) {
    if (typeof options.hints !== 'number') throw invalidType('options.hints', 'of type number', options.hints);
    out.hints = options.hints >>> 0;
    if ((out.hints & ~(ADDRCONFIG | ALL | V4MAPPED)) !== 0) throw invalidValue('hints', options.hints);
  }
  if (options.family != null) {
    if (options.family === 'IPv4') out.family = 4;
    else if (options.family === 'IPv6') out.family = 6;
    else { validateOneOf(options.family, 'options.family', [0, 4, 6]); out.family = options.family; }
  }
  if (options.all != null) { validateBoolean(options.all, 'options.all'); out.all = options.all; }
  if (options.verbatim != null) { validateBoolean(options.verbatim, 'options.verbatim'); out.order = options.verbatim ? 'verbatim' : 'ipv4first'; }
  if (options.order != null) { validateOneOf(options.order, 'options.order', ['verbatim', 'ipv4first', 'ipv6first']); out.order = options.order; }
  return out;
}

/** The addresses for `hostname`, as getaddrinfo gives them with `options`; rejects with Node's error. */
async function addressesFor(hostname, { family, hints, order }) {
  const mapped = family === 6 && (hints & V4MAPPED) !== 0;
  let found;
  try {
    found = await internals.lookup({ hostname, family: mapped ? 0 : family });
  } catch (err) {
    throw dnsError(err, 'getaddrinfo', hostname);
  }
  if (mapped) {
    const v6 = found.filter((a) => a.family === 6);
    const v4 = found.filter((a) => a.family === 4).map((a) => ({ address: `::ffff:${a.address}`, family: 6 }));
    found = (hints & ALL) !== 0 ? [...v6, ...v4] : (v6.length ? v6 : v4);
    if (!found.length) throw new DNSException('ENOTFOUND', 'getaddrinfo', hostname, GAI_ERRNO.ENOTFOUND);
  }
  if (order === 'ipv4first') found = [...found.filter((a) => a.family === 4), ...found.filter((a) => a.family === 6)];
  else if (order === 'ipv6first') found = [...found.filter((a) => a.family === 6), ...found.filter((a) => a.family === 4)];
  return found;
}

// Node 26 refuses a hostname that's empty or missing; one that isn't a string is the wrong type.
const emptyHostname = (hostname) => invalidValue('hostname', hostname, 'must be a non-empty string');

export function lookup(hostname, options, callback) {
  if (!hostname) throw emptyHostname(hostname);
  validateString(hostname, 'hostname');
  if (typeof options === 'function') { callback = options; options = undefined; } else validateFunction(callback, 'callback');
  const settings = lookupOptions(options);
  const literal = isIP(hostname);
  if (literal) {
    if (settings.all) later(callback, null, [{ address: hostname, family: literal }]);
    else later(callback, null, hostname, literal);
    return {};
  }
  addressesFor(hostname, settings).then((found) => {
    if (settings.all) later(callback, null, found);
    else later(callback, null, found[0].address, found[0].family);
  }, (err) => later(callback, err));
  return {};
}
// util.promisify(dns.lookup) resolves with { address, family }, as Node's does.
Object.defineProperty(lookup, Symbol.for('nodejs.util.promisify.custom'), {
  value: (hostname, options) => promises.lookup(hostname, options), enumerable: false, configurable: true,
});

function promiseLookup(hostname, options) {
  if (hostname) validateString(hostname, 'hostname');
  const settings = lookupOptions(options);
  if (!hostname) return Promise.reject(emptyHostname(hostname));
  const literal = isIP(hostname);
  if (literal) {
    const one = { address: hostname, family: literal };
    return Promise.resolve(settings.all ? [one] : one);
  }
  return addressesFor(hostname, settings).then((found) => (settings.all ? found : found[0]));
}

function checkLookupService(address, port) {
  if (arguments.length < 2) throw typeError('ERR_MISSING_ARGS', 'The "address", "port", and "callback" arguments must be specified');
  validateString(address, 'address');
  if (!isIP(address)) throw invalidValue('address', address);
  const number = typeof port === 'string' && port.trim() !== '' ? Number(port) : port;
  if (typeof number !== 'number' || !Number.isInteger(number) || number < 0 || number > 65535) {
    throw Object.assign(new RangeError(`Port should be >= 0 and < 65536. Received ${describe(port)}.`), { code: 'ERR_SOCKET_BAD_PORT' });
  }
  return number;
}

export function lookupService(address, port, callback) {
  const number = checkLookupService(address, port);
  validateFunction(callback, 'callback');
  internals.lookupService({ address, port: number }).then(({ hostname, service }) => later(callback, null, hostname, service),
    (err) => later(callback, dnsError(err, 'getnameinfo', address)));
  return {};
}

// --- queries, as c-ares asks them ---

/** Each query: its c-ares name, the record type and the answer's shape. */
const QUERIES = {
  resolve4: ['queryA', 'A'], resolve6: ['queryAaaa', 'AAAA'], resolveAny: ['queryAny', 'ANY'], resolveCaa: ['queryCaa', 'CAA'],
  resolveCname: ['queryCname', 'CNAME'], resolveMx: ['queryMx', 'MX'], resolveNaptr: ['queryNaptr', 'NAPTR'], resolveNs: ['queryNs', 'NS'],
  resolvePtr: ['queryPtr', 'PTR'], resolveSoa: ['querySoa', 'SOA'], resolveSrv: ['querySrv', 'SRV'], resolveTlsa: ['queryTlsa', 'TLSA'],
  resolveTxt: ['queryTxt', 'TXT'],
};
const BY_TYPE = Object.fromEntries(Object.entries(QUERIES).map(([method, [, type]]) => [type, method]));

const bytes = (base64) => {
  const binary = atob(base64);
  const out = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) out[i] = binary.charCodeAt(i);
  return out.buffer;
};

/** One record as Node gives it in an ANY answer: its fields, then its type. */
function anyRecord(record) {
  const { type, ttl } = record;
  switch (type) {
    case 'A': case 'AAAA': return { address: record.address, ttl, type };
    case 'CNAME': case 'NS': case 'PTR': return { value: record.value, type };
    case 'TXT': return { entries: record.entries, type };
    case 'MX': return { exchange: record.exchange, priority: record.priority, type };
    case 'SRV': return { name: record.name, port: record.port, priority: record.priority, weight: record.weight, type };
    case 'SOA': return { ...soa(record), type };
    case 'NAPTR': return { ...naptr(record), type };
    case 'CAA': return caa(record);
    case 'TLSA': return { ...tlsa(record), type };
    default: return { type };
  }
}
const soa = (r) => ({ nsname: r.nsname, hostmaster: r.hostmaster, serial: r.serial, refresh: r.refresh, retry: r.retry, expire: r.expire, minttl: r.minttl });
const naptr = (r) => ({ flags: r.flags, service: r.service, regexp: r.regexp, replacement: r.replacement, order: r.order, preference: r.preference });
const tlsa = (r) => ({ certUsage: r.certUsage, selector: r.selector, match: r.match, data: bytes(r.data) });
function caa(r) {
  const out = { critical: r.critical, type: 'CAA' };
  for (const [key, value] of Object.entries(r)) if (!['type', 'ttl', 'critical'].includes(key)) out[key] = value;
  return out;
}

/** The records of one query as its function answers them. */
function shape(type, records, ttl) {
  switch (type) {
    case 'A': case 'AAAA': return records.map((r) => (ttl ? { address: r.address, ttl: r.ttl } : r.address));
    case 'CNAME': case 'NS': case 'PTR': return records.map((r) => r.value);
    case 'TXT': return records.map((r) => r.entries);
    case 'MX': return records.map((r) => ({ exchange: r.exchange, priority: r.priority, type: 'MX' }));
    case 'SRV': return records.map((r) => ({ name: r.name, port: r.port, priority: r.priority, weight: r.weight, type: 'SRV' }));
    case 'SOA': return soa(records[0]);
    case 'NAPTR': return records.map(naptr);
    case 'CAA': return records.map(caa);
    case 'TLSA': return records.map(tlsa);
    default: return [...records].sort((a, b) => ANY_ORDER.indexOf(a.type) - ANY_ORDER.indexOf(b.type)).map(anyRecord);
  }
}
// c-ares's ANY answer, as Node builds it: each type in this order, records of a type as they came.
const ANY_ORDER = ['A', 'CNAME', 'AAAA', 'MX', 'NS', 'TXT', 'SRV', 'PTR', 'NAPTR', 'SOA', 'CAA', 'TLSA'];

const settings = Symbol('settings');

class ResolverBase {
  constructor(options = undefined) {
    let timeout = -1;
    let tries = 4;
    if (options != null) {
      if (typeof options !== 'object') throw invalidType('options', 'of type object', options);
      if (options.timeout !== undefined) {
        if (!Number.isInteger(options.timeout)) throw invalidType('options.timeout', 'of type number', options.timeout);
        if (options.timeout < -1 || options.timeout > 2 ** 31 - 1) {
          throw Object.assign(new RangeError(`The value of "options.timeout" is out of range. It must be >= -1 && <= 2147483647. Received ${options.timeout}`), { code: 'ERR_OUT_OF_RANGE' });
        }
        timeout = options.timeout;
      }
      if (options.tries !== undefined) {
        if (!Number.isInteger(options.tries)) throw invalidType('options.tries', 'of type number', options.tries);
        if (options.tries < 1 || options.tries > 2 ** 31 - 1) {
          throw Object.assign(new RangeError(`The value of "options.tries" is out of range. It must be >= 1 && <= 2147483647. Received ${options.tries}`), { code: 'ERR_OUT_OF_RANGE' });
        }
        tries = options.tries;
      }
    }
    this[settings] = { timeout, tries, servers: null, pending: new Set() };
  }

  /** Fails every query in flight with ECANCELLED, as Node's. */
  cancel() {
    for (const fail of [...this[settings].pending]) fail();
  }

  getServers() {
    return this[settings].servers ? [...this[settings].servers] : internals.servers();
  }

  /**
   * The script's own servers, as Node takes them ("1.2.3.4", "1.2.3.4:53", "::1", "[::1]:53"). They're asked instead of
   * the cluster's, once the egress gate allows each as a dns connection.
   */
  setServers(servers) {
    if (!Array.isArray(servers)) throw invalidType('servers', 'an instance of Array', servers);
    const out = [];
    servers.forEach((server, i) => {
      validateString(server, `servers[${i}]`);
      let host = server;
      let port = 53;
      const bracketed = /^\[([^\]]+)\](?::(\d+))?$/.exec(server);
      if (bracketed) { host = bracketed[1]; if (bracketed[2]) port = Number(bracketed[2]); } else if (isIP(server) !== 6) {
        const withPort = /^([^:]+):(\d+)$/.exec(server);
        if (withPort) { host = withPort[1]; port = Number(withPort[2]); }
      }
      if (!isIP(host) || port < 1 || port > 65535) {
        throw Object.assign(new TypeError(`Invalid IP address: ${server}`), { code: 'ERR_INVALID_IP_ADDRESS' });
      }
      out.push(port === 53 ? host : isIP(host) === 6 ? `[${host}]:${port}` : `${host}:${port}`);
    });
    this[settings].servers = out.length ? out : null;
  }

  setLocalAddress(ipv4, ipv6) {
    validateString(ipv4, 'ipv4');
    if (isIP(ipv4) !== 4) throw Object.assign(new TypeError(`Invalid IP address: ${ipv4}`), { code: 'ERR_INVALID_IP_ADDRESS' });
    if (ipv6 !== undefined) {
      validateString(ipv6, 'ipv6');
      if (isIP(ipv6) !== 6) throw Object.assign(new TypeError(`Invalid IP address: ${ipv6}`), { code: 'ERR_INVALID_IP_ADDRESS' });
    }
  }

  /** Asks the host, failing with ECANCELLED if cancel() comes first. */
  _ask(call, syscall, name) {
    const own = this[settings];
    return new Promise((resolve, reject) => {
      const fail = () => { own.pending.delete(fail); reject(new DNSException('ECANCELLED', syscall, name)); };
      own.pending.add(fail);
      call({ servers: own.servers ?? undefined, timeout: own.timeout, tries: own.tries }).then((answer) => {
        if (!own.pending.delete(fail)) return;
        resolve(answer);
      }, (err) => {
        if (!own.pending.delete(fail)) return;
        reject(dnsError(err, syscall, name));
      });
    });
  }

  _query(method, name, options) {
    const [syscall, type] = QUERIES[method];
    const ttl = Boolean(options?.ttl);
    return this._ask((common) => internals.resolve({ name, type, ...common }), syscall, name)
      .then((records) => shape(type, records, ttl));
  }

  _reverse(ip) {
    return this._ask((common) => internals.reverse({ address: ip, ...common }), 'getHostByAddr', ip);
  }
}

export class Resolver extends ResolverBase {
  resolve(hostname, rrtype, callback) {
    if (typeof rrtype === 'function') { callback = rrtype; rrtype = 'A'; } else if (typeof rrtype !== 'string') throw invalidType('rrtype', 'of type string', rrtype);
    const method = BY_TYPE[rrtype];
    if (!method) throw invalidValue('rrtype', rrtype);
    return this[method](hostname, callback);
  }

  reverse(ip, callback) {
    validateString(ip, 'ip');
    validateFunction(callback, 'callback');
    if (!isIP(ip)) throw new DNSException('EINVAL', 'getHostByAddr', ip, -22);
    this._reverse(ip).then((names) => later(callback, null, names), (err) => later(callback, err));
    return {};
  }
}
for (const method of Object.keys(QUERIES)) {
  Resolver.prototype[method] = function query(name, options, callback) {
    if (arguments.length <= 2) { callback = options; options = undefined; }
    validateString(name, 'name');
    validateFunction(callback, 'callback');
    this._query(method, name, options).then((answer) => later(callback, null, answer), (err) => later(callback, err));
    return {};
  };
}

class PromiseResolver extends ResolverBase {
  resolve(hostname, rrtype = 'A') {
    if (typeof rrtype !== 'string') throw invalidType('rrtype', 'of type string', rrtype);
    const method = BY_TYPE[rrtype];
    if (!method) throw invalidValue('rrtype', rrtype);
    return this[method](hostname);
  }

  reverse(ip) {
    validateString(ip, 'ip');
    if (!isIP(ip)) throw new DNSException('EINVAL', 'getHostByAddr', ip, -22);
    return this._reverse(ip);
  }
}
for (const method of Object.keys(QUERIES)) {
  PromiseResolver.prototype[method] = function query(name, options) {
    validateString(name, 'name');
    return this._query(method, name, options);
  };
}

// The module's functions use one default resolver, shared with dns.promises, as Node's do.
const defaultResolver = new Resolver();
const promiseDefault = new PromiseResolver();
promiseDefault[settings] = defaultResolver[settings];

const bindAll = (resolver, names) => Object.fromEntries(names.map((name) => [name, resolver[name].bind(resolver)]));
const METHODS = ['resolve', ...Object.keys(QUERIES), 'reverse', 'getServers', 'setServers'];

export const {
  resolve, resolve4, resolve6, resolveAny, resolveCaa, resolveCname, resolveMx, resolveNaptr, resolveNs, resolvePtr, resolveSoa,
  resolveSrv, resolveTlsa, resolveTxt, reverse, getServers, setServers,
} = bindAll(defaultResolver, METHODS);

export const promises = {
  ...codes,
  Resolver: PromiseResolver,
  lookup: promiseLookup,
  lookupService: (address, port) => {
    const number = checkLookupService(address, port);
    return internals.lookupService({ address, port: number }).catch((err) => { throw dnsError(err, 'getnameinfo', address); });
  },
  getDefaultResultOrder,
  setDefaultResultOrder,
  ...bindAll(promiseDefault, METHODS),
};

export default {
  ...codes, ADDRCONFIG, ALL, V4MAPPED, lookup, lookupService, Resolver, getDefaultResultOrder, setDefaultResultOrder, getServers, setServers,
  resolve, resolve4, resolve6, resolveAny, resolveCaa, resolveCname, resolveMx, resolveNaptr, resolveNs, resolvePtr, resolveSoa, resolveSrv,
  resolveTlsa, resolveTxt, reverse, promises,
};
