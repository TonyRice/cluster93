// A stand-in for Node's `https` (D-109): node:http's client with TLS. Certificates are always verified against the
// JVM's trust store, unless a request passes its own `ca`, or `rejectUnauthorized: false` (honoured: it only affects
// the job's own connection, and the job's log says so once).
import { Agent as HttpAgent, ClientRequest } from 'node:http';

export class Agent extends HttpAgent {
  constructor(options = {}) {
    super(options);
    this.defaultPort = 443;
    this.protocol = 'https:';
    this.maxCachedSessions = options.maxCachedSessions ?? 100;
  }
}
export const globalAgent = new Agent({ keepAlive: true, scheduling: 'lifo', timeout: 5000 });

const HTTPS = { protocol: 'https:', defaultPort: 443, tls: true, get agent() { return globalAgent; } };

export function request(input, options, callback) {
  return new ClientRequest(input, options, callback, HTTPS);
}

export function get(input, options, callback) {
  const req = request(input, options, callback);
  req.end();
  return req;
}

export function createServer() {
  const err = new Error("https.createServer isn't available in the isolate: a service serves HTTPS through the cluster's ingress");
  err.code = 'ERR_NOT_SUPPORTED';
  throw err;
}
export class Server {
  constructor() { createServer(); }
}

export default { Agent, globalAgent, request, get, createServer, Server };
