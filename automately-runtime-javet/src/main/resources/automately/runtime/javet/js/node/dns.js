// A stand-in for Node's `dns` until phase B's dns step (network-reach.md §6), which answers lookups through the egress
// rules (D-114). Until then it has Node's names and constants, so libraries that import it at load (got's
// cacheable-lookup, ioredis) load; every lookup fails with ERR_NOT_SUPPORTED. fetch, http and https never need it:
// the host resolves names itself, through the egress gate.
import { nextTick } from 'node:process';

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

function notYet(syscall, hostname) {
  const err = new Error(`dns.${syscall}() isn't available in the isolate yet; fetch, http and https resolve names themselves`);
  err.code = 'ERR_NOT_SUPPORTED';
  err.syscall = syscall;
  if (hostname !== undefined) err.hostname = hostname;
  return err;
}

const QUERIES = ['resolve', 'resolve4', 'resolve6', 'resolveAny', 'resolveCaa', 'resolveCname', 'resolveMx', 'resolveNaptr', 'resolveNs',
  'resolvePtr', 'resolveSoa', 'resolveSrv', 'resolveTlsa', 'resolveTxt', 'reverse'];

/** A callback API: the last function argument is called back with the error, on the next tick. */
const callbackQuery = (syscall) => (hostname, ...rest) => {
  const callback = rest.reverse().find((arg) => typeof arg === 'function');
  if (typeof callback !== 'function') {
    const err = new TypeError('The "callback" argument must be of type function');
    err.code = 'ERR_INVALID_ARG_TYPE';
    throw err;
  }
  nextTick(callback, notYet(syscall, hostname));
  return {};
};
const promiseQuery = (syscall) => (hostname) => Promise.reject(notYet(syscall, hostname));

let order = 'verbatim';
export function getDefaultResultOrder() { return order; }
export function setDefaultResultOrder(value) {
  if (!['ipv4first', 'ipv6first', 'verbatim'].includes(value)) {
    const err = new TypeError(`The argument 'order' must be one of: 'verbatim', 'ipv4first', 'ipv6first'. Received '${value}'`);
    err.code = 'ERR_INVALID_ARG_VALUE';
    throw err;
  }
  order = value;
}
export function getServers() { return []; }
export function setServers() { throw notYet('setServers'); }

export const lookup = callbackQuery('lookup');
export const lookupService = (address, port, callback) => callbackQuery('getnameinfo')(address, port, callback);
export const {
  resolve, resolve4, resolve6, resolveAny, resolveCaa, resolveCname, resolveMx, resolveNaptr, resolveNs, resolvePtr, resolveSoa,
  resolveSrv, resolveTlsa, resolveTxt, reverse,
} = Object.fromEntries(QUERIES.map((name) => [name, callbackQuery(name === 'reverse' ? 'getHostByAddr' : 'query' + name.slice(7))]));

class BaseResolver {
  constructor(options) { this._options = options; }
  cancel() {}
  getServers() { return []; }
  setServers() { throw notYet('setServers'); }
  setLocalAddress() {}
}

export class Resolver extends BaseResolver {}
for (const name of QUERIES) Resolver.prototype[name] = callbackQuery(name);

class PromiseResolver extends BaseResolver {}
for (const name of QUERIES) PromiseResolver.prototype[name] = promiseQuery(name);

export const promises = {
  ...codes,
  Resolver: PromiseResolver,
  lookup: promiseQuery('lookup'),
  lookupService: (address) => Promise.reject(notYet('getnameinfo', address)),
  getDefaultResultOrder,
  setDefaultResultOrder,
  getServers,
  setServers,
  ...Object.fromEntries(QUERIES.map((name) => [name, promiseQuery(name)])),
};

export default {
  ...codes, ADDRCONFIG, ALL, V4MAPPED, lookup, lookupService, Resolver, getDefaultResultOrder, setDefaultResultOrder, getServers, setServers,
  resolve, resolve4, resolve6, resolveAny, resolveCaa, resolveCname, resolveMx, resolveNaptr, resolveNs, resolvePtr, resolveSoa, resolveSrv,
  resolveTlsa, resolveTxt, reverse, promises,
};
