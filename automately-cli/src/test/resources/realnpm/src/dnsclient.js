// node:dns (D-122) against the test's DNS server (TEST_DNS), and the system's resolver for lookup. Runs the same under
// Node: both set TEST_DNS as their server, which the isolate's egress gate allows under the development switch.
import dns from 'node:dns';
import dnsPromises from 'node:dns/promises';
import { promisify } from 'node:util';

const error = (e) => ({ name: e.name, message: e.message, keys: Object.keys(e), errno: e.errno, code: e.code, syscall: e.syscall, hostname: e.hostname });
const settle = (p) => p.then((value) => ({ value }), (e) => ({ error: error(e) }));
const thrown = (fn) => { try { fn(); return 'no error'; } catch (e) { return error(e); } };
const viaCallback = (fn, ...args) => new Promise((resolve) => fn(...args, (err, ...results) => resolve(err ? { error: error(err) } : { results })));
const hex = (buffer) => Buffer.from(buffer).toString('hex');

export async function run() {
  const out = {};
  const server = process.env.TEST_DNS;
  dns.setServers([server]);
  out.servers = dns.getServers();
  out.sharedWithPromises = dnsPromises.getServers();
  const r = dns.promises;

  out.answers = {
    a: await r.resolve4('example.test'),
    aTtl: await r.resolve4('example.test', { ttl: true }),
    aaaa: await r.resolve6('example.test'),
    mx: await r.resolveMx('example.test'),
    txt: await r.resolveTxt('example.test'),
    ns: await r.resolveNs('example.test'),
    soa: await r.resolveSoa('example.test'),
    caa: await r.resolveCaa('example.test'),
    naptr: await r.resolveNaptr('example.test'),
    srv: await r.resolveSrv('_sip._tcp.example.test'),
    tlsa: (await r.resolveTlsa('_443._tcp.example.test')).map((t) => ({ ...t, data: hex(t.data) })),
    cname: await r.resolveCname('www.example.test'),
    ptr: await r.resolvePtr('34.216.184.93.in-addr.arpa'),
    reverse: await r.reverse('93.184.216.34'),
    any: (await r.resolveAny('example.test')).map((rec) => (rec.type === 'TLSA' ? { ...rec, data: hex(rec.data) } : rec)),
    byType: await r.resolve('example.test', 'MX'),
    defaultType: await r.resolve('example.test'),
    overTcp: (await r.resolveTxt('big.example.test')).map((entry) => entry.map((s) => s.length)),
  };

  out.errors = {
    notFound: await settle(r.resolve4('nothing.example.test')),
    noData: await settle(r.resolveMx('www.example.test')),
    servfail: await settle(r.resolve4('servfail.example.test')),
    refused: await settle(r.resolve4('refused.example.test')),
    notimp: await settle(r.resolve4('notimp.example.test')),
    reverseNotFound: await settle(r.reverse('93.184.216.99')),
  };

  // A resolver of its own: a short timeout and one try, and cancel().
  const quick = new dns.promises.Resolver({ timeout: 200, tries: 1 });
  quick.setServers([server]);
  out.timeout = await settle(quick.resolve4('silent.example.test'));
  const cancelled = new dns.Resolver({ timeout: 5000, tries: 1 });
  cancelled.setServers([server]);
  const pending = viaCallback(cancelled.resolve4.bind(cancelled), 'silent.example.test');
  cancelled.cancel();
  out.cancelled = await pending;
  out.ownServers = [cancelled.getServers(), new dns.Resolver().getServers().length > 0];

  out.callbacks = {
    a: await viaCallback(dns.resolve4, 'example.test'),
    aTtl: await viaCallback(dns.resolve4, 'example.test', { ttl: true }),
    mx: await viaCallback(dns.resolve, 'example.test', 'MX'),
    soa: await viaCallback(dns.resolveSoa, 'example.test'),
    reverse: await viaCallback(dns.reverse, '93.184.216.34'),
    notFound: await viaCallback(dns.resolveTxt, 'nothing.example.test'),
  };

  out.validation = {
    badType: thrown(() => dns.resolve('example.test', 'BOGUS', () => {})),
    typeNotString: thrown(() => dns.resolve('example.test', 5, () => {})),
    nameNotString: thrown(() => dns.resolve4(5, () => {})),
    noCallback: thrown(() => dns.resolve4('example.test')),
    badServer: thrown(() => dns.setServers(['not-an-ip'])),
    serversNotArray: thrown(() => dns.setServers('1.1.1.1')),
    reverseNotIp: thrown(() => dns.reverse('example.test', () => {})),
    lookupNotString: thrown(() => dns.lookup(5, () => {})),
    lookupBadFamily: thrown(() => dns.lookup('localhost', { family: 7 }, () => {})),
    lookupBadOrder: thrown(() => dns.lookup('localhost', { order: 'random' }, () => {})),
    lookupServiceNotIp: thrown(() => dns.lookupService('localhost', 22, () => {})),
    lookupServiceBadPort: thrown(() => dns.lookupService('127.0.0.1', 99999, () => {})),
    badTimeout: thrown(() => new dns.Resolver({ timeout: 'soon' })),
    badOrder: thrown(() => dns.setDefaultResultOrder('random')),
  };

  // lookup asks the operating system, as getaddrinfo: hosts files and *.localhost, in the system's order.
  out.lookup = {
    one: await r.lookup('localhost'),
    all: await r.lookup('localhost', { all: true }),
    v4: await r.lookup('localhost', { family: 4 }),
    v6: await r.lookup('localhost', 6),
    ipv4first: await r.lookup('mongo1.cluster.localhost', { all: true, order: 'ipv4first' }),
    ipv6first: await r.lookup('mongo1.cluster.localhost', { all: true, order: 'ipv6first' }),
    verbatim: await r.lookup('mongo1.cluster.localhost', { all: true }),
    literal: await r.lookup('93.184.216.34', { all: true }),
    notFound: await settle(r.lookup('no-such-name.invalid')),
    callback: await viaCallback(dns.lookup, 'localhost'),
    callbackAll: await viaCallback(dns.lookup, 'localhost', { all: true }),
    empty: thrown(() => dns.lookup('', () => {})),
    missing: thrown(() => dns.lookup(undefined, () => {})),
    emptyPromise: await settle(r.lookup('')),
    promisified: await promisify(dns.lookup)('localhost'),
    service: await r.lookupService('127.0.0.1', 22),
    serviceCallback: await viaCallback(dns.lookupService, '127.0.0.1', 80),
  };
  out.order = dns.getDefaultResultOrder();
  out.constants = [dns.NOTFOUND, dns.NODATA, dns.ADDRCONFIG, dns.V4MAPPED, dns.ALL];
  return out;
}
