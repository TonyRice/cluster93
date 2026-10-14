// got with http2: true (D-117), through http2-wrapper, against the test's HTTP/2 server (TEST_H2, with TEST_CA).
// Runs the same under Node.
import got from 'got';

export async function run() {
  const client = got.extend({ http2: true, https: { certificateAuthority: process.env.TEST_CA }, retry: { limit: 0 } });
  const json = await client(process.env.TEST_H2 + '/json');
  const echo = await client.post(process.env.TEST_H2 + '/echo', { json: { a: 1 } }).json();
  const failure = await client(process.env.TEST_H2 + '/missing').catch((e) => [e.name, e.response?.statusCode]);
  return {
    status: json.statusCode, httpVersion: json.httpVersion, type: json.headers['content-type'], body: JSON.parse(json.body),
    echo: { method: echo.method, path: echo.path, body: echo.body }, failure,
  };
}
