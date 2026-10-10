// A stand-in for Node's `https` (D-109): node:http's client with TLS. Certificates are always verified against the
// JVM's trust store, unless a request passes its own `ca`, or `rejectUnauthorized: false` (honoured: it only affects
// the job's own connection, and the job's log says so once).
import { Agent as HttpAgent, ClientRequest, Server as HttpServer } from 'node:http';

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

// An HTTPS server is an HTTP server here (D-112, D-123): the cluster's ingress terminates TLS with the cluster's (or
// your domain's) certificate, so key and cert are noted and set aside. Requests that came over TLS say so
// (req.socket.encrypted), as they would on an HTTPS server.
let noted = false;
export class Server extends HttpServer {
  constructor(options, requestListener) {
    if (typeof options === 'function') { requestListener = options; options = {}; }
    super(options, requestListener);
    if (!noted && options && (options.key || options.cert || options.pfx)) {
      noted = true;
      console.warn('https.createServer: key and cert are set aside; the cluster\'s ingress serves TLS for your service');
    }
  }
}
export function createServer(options, requestListener) {
  return new Server(options, requestListener);
}

export default { Agent, globalAgent, request, get, createServer, Server };
