// Service-like: what a request handler does with no I/O. Route matching, query parsing, a JSON body in and out.
const routes = [
  [/^\/users\/(\d+)$/, (m, req) => ({ user: Number(m[1]), fields: req.query.fields?.split(',') ?? [] })],
  [/^\/orders\/(\w+)\/items$/, (m, req) => ({ order: m[1], items: req.body.items.map((x) => ({ ...x, total: x.qty * x.price })) })],
  [/^\/search$/, (m, req) => ({ q: req.query.q, hits: Array.from({ length: 20 }, (_, k) => ({ id: k, score: 1 / (k + 1) })) })],
];
function handle(raw) {
  const [path, qs = ''] = raw.url.split('?');
  const query = Object.fromEntries(new URLSearchParams(qs));
  const body = raw.body ? JSON.parse(raw.body) : null;
  for (const [pattern, handler] of routes) {
    const m = pattern.exec(path);
    if (m) return { status: 200, headers: { 'content-type': 'application/json' }, body: JSON.stringify(handler(m, { query, body, headers: raw.headers })) };
  }
  return { status: 404, body: '' };
}
const requests = [
  { url: '/users/42?fields=name,email,created', headers: { accept: 'application/json' } },
  { url: '/orders/abc123/items', headers: {}, body: JSON.stringify({ items: Array.from({ length: 10 }, (_, k) => ({ sku: 's' + k, qty: k + 1, price: 9.99 })) }) },
  { url: '/search?q=automately&page=2', headers: {} },
  { url: '/missing', headers: {} },
];
function iteration() {
  let bytes = 0;
  for (let k = 0; k < 5000; k++) bytes += handle(requests[k % requests.length]).body.length;
  return bytes;
}
