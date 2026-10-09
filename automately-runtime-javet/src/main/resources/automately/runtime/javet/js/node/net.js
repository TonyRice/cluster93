// A stand-in for Node's `net` (D-109): address checks (isIP, isIPv4, isIPv6, as Node's), so libraries that import it for
// those (node-fetch) or reference it at load (https-proxy-agent) work. Sockets aren't available yet: connecting or
// serving throws ERR_NOT_SUPPORTED. Outbound sockets through the egress gate are phase B5 (docs/design/network-reach.md).
import { EventEmitter } from 'node:events';

// Node's own patterns (lib/internal/net.js).
const v4Seg = '(?:25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9][0-9]|[0-9])';
const v4Str = `(?:${v4Seg}\\.){3}${v4Seg}`;
const IPv4Reg = new RegExp(`^${v4Str}$`);
const v6Seg = '(?:[0-9a-fA-F]{1,4})';
const IPv6Reg = new RegExp('^(?:' +
  `(?:${v6Seg}:){7}(?:${v6Seg}|:)|` +
  `(?:${v6Seg}:){6}(?:${v4Str}|:${v6Seg}|:)|` +
  `(?:${v6Seg}:){5}(?::${v4Str}|(?::${v6Seg}){1,2}|:)|` +
  `(?:${v6Seg}:){4}(?:(?::${v6Seg}){0,1}:${v4Str}|(?::${v6Seg}){1,3}|:)|` +
  `(?:${v6Seg}:){3}(?:(?::${v6Seg}){0,2}:${v4Str}|(?::${v6Seg}){1,4}|:)|` +
  `(?:${v6Seg}:){2}(?:(?::${v6Seg}){0,3}:${v4Str}|(?::${v6Seg}){1,5}|:)|` +
  `(?:${v6Seg}:){1}(?:(?::${v6Seg}){0,4}:${v4Str}|(?::${v6Seg}){1,6}|:)|` +
  `(?::(?:(?::${v6Seg}){0,5}:${v4Str}|(?::${v6Seg}){1,7}|:))` +
')(?:%[0-9a-zA-Z-.:]{1,})?$');

export function isIPv4(input) { return IPv4Reg.test(`${input}`); }
export function isIPv6(input) { return IPv6Reg.test(`${input}`); }
export function isIP(input) { return isIPv4(input) ? 4 : isIPv6(input) ? 6 : 0; }

function unsupported(what) {
  const err = new Error(`${what} isn't available in the isolate yet: outbound sockets come with phase B5; use node:http or fetch`);
  err.code = 'ERR_NOT_SUPPORTED';
  return err;
}

/** Exists so `instanceof net.Socket` checks work; making one throws. */
export class Socket extends EventEmitter {
  constructor() { super(); throw unsupported('net.Socket'); }
}
export const Stream = Socket;
export class Server extends EventEmitter {
  constructor() { super(); throw unsupported('net.Server'); }
}
export function connect() { throw unsupported('net.connect'); }
export const createConnection = connect;
export function createServer() { throw unsupported('net.createServer'); }
let autoSelectFamily = true;
export function getDefaultAutoSelectFamily() { return autoSelectFamily; }
export function setDefaultAutoSelectFamily(value) { autoSelectFamily = Boolean(value); }

export default { isIP, isIPv4, isIPv6, Socket, Stream, Server, connect, createConnection, createServer,
  getDefaultAutoSelectFamily, setDefaultAutoSelectFamily };
