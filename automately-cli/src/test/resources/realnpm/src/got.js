// got over HTTP/1.1 (D-114), against the test's local server (TEST_HTTP). Runs the same under Node.
import got, { HTTPError } from 'got';
import { pipeline } from 'node:stream/promises';
import { Writable } from 'node:stream';

const base = process.env.TEST_HTTP;
// No waiting between retries, so the run stays quick; the count is what's compared.
const quick = { retry: { limit: 2, calculateDelay: ({ computedValue }) => (computedValue ? 5 : 0) } };

const failure = async (promise) => {
  try {
    await promise;
    return 'succeeded';
  } catch (e) {
    return { name: e.name, code: e.code, status: e.response?.statusCode, retries: e.response?.retryCount, event: e.event,
      message: e.message.replace(/:\d+/g, ':PORT') };
  }
};

export async function run() {
  const out = {};
  out.json = await got(base + '/json').json();

  const r = await got(base + '/json');
  out.meta = {
    status: r.statusCode, ok: r.ok, type: r.headers['content-type'], cookies: r.headers['set-cookie'], repeat: r.headers['x-repeat'],
    url: r.url.replace(base, ''), redirects: r.redirectUrls.length, retries: r.retryCount, fromCache: r.isFromCache, ip: r.ip,
  };
  // Which timings and phases exist: the values differ run to run, which ones are there doesn't.
  out.timings = ['start', 'socket', 'lookup', 'connect', 'secureConnect', 'upload', 'response', 'end'].filter((k) => typeof r.timings[k] === 'number');
  out.phases = Object.keys(r.timings.phases).filter((k) => r.timings.phases[k] !== undefined).sort();

  out.postJson = await got.post(base + '/echo', { json: { a: 1, b: 'two' } }).json();
  out.postForm = await got.post(base + '/echo', { form: { x: 'y z', n: 2 } }).json();
  out.postBuffer = await got.put(base + '/echo', { body: Buffer.from([0xff, 0, 0x80]) }).json();
  out.searchParams = (await got(base + '/echo', { searchParams: { q: 'a b', n: 2 } }).json()).url;
  out.headers = (await got(base + '/echo', { headers: { 'x-custom': 'yes' }, username: 'me', password: 'pa ss' }).json());

  // Hooks see the request and the response.
  const seen = [];
  const hooked = got.extend({
    prefixUrl: base,
    hooks: {
      beforeRequest: [(options) => { options.headers['x-custom'] = 'from-hook'; seen.push('beforeRequest'); }],
      afterResponse: [(response) => { seen.push('afterResponse ' + response.statusCode); return response; }],
    },
  });
  out.hooked = { custom: (await hooked('echo').json()).custom, seen };

  out.redirect = await got(base + '/redirect').then((res) => ({ url: res.url.replace(base, ''), redirects: res.redirectUrls.map((u) => u.pathname) }));
  out.gzip = await got(base + '/gzip').json();
  out.text = await got(base + '/status/200').text();

  out.httpError = await failure(got(base + '/status/418', { retry: { limit: 0 } }));
  out.isHttpError = await got(base + '/status/404', { retry: { limit: 0 } }).catch((e) => e instanceof HTTPError);
  out.retried = await failure(got(base + '/status/503', quick));
  out.noThrow = await got(base + '/status/500', { throwHttpErrors: false, retry: { limit: 0 } }).then((res) => res.statusCode);
  out.timeout = await failure(got(base + '/slow', { timeout: { request: 200 }, retry: { limit: 0 } }));
  out.refused = await failure(got('http://127.0.0.1:9/', { retry: { limit: 0 } }));

  let bytes = 0;
  let progress = 0;
  const stream = got.stream(base + '/big');
  stream.on('downloadProgress', (p) => { progress = p.percent; });
  await pipeline(stream, new Writable({ write(chunk, enc, cb) { bytes += chunk.length; cb(); } }));
  out.stream = { bytes, progress };

  out.paginated = await got.paginate.all(base + '/paged', { responseType: 'json' });
  return out;
}
