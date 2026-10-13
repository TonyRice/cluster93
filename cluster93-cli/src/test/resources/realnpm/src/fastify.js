// Fastify 5 as a service (D-123): it only listens, on localhost as Fastify does by default (both of its addresses).
// RealNpmPackagesTest sends the same requests to it under real Node and through the cluster's ingress, and compares.
import Fastify from 'fastify';
import { Readable } from 'node:stream';

const app = Fastify();
const events = [];
app.addContentTypeParser('application/x-www-form-urlencoded', { parseAs: 'string' },
  (req, body, done) => done(null, Object.fromEntries(new URLSearchParams(body))));
app.addContentTypeParser('application/octet-stream', { parseAs: 'buffer', bodyLimit: 16 * 1024 * 1024 }, (req, body, done) => done(null, body));
app.setErrorHandler((err, req, reply) => reply.status(500).send({ error: err.message }));
app.get('/hello', async () => 'hello');
app.get('/json', async () => ({ hello: 'world', n: 1 }));
app.get('/users/:id', async (req) => ({ id: req.params.id, expand: req.query.expand ?? null }));
app.post('/echo', async (req) => ({ received: req.body }));
app.post('/form', async (req) => req.body);
app.get('/cookies', async (req, reply) => {
  reply.header('set-cookie', ['a=1; Path=/', 'b=2; Path=/; HttpOnly']);
  return 'cookies';
});
app.get('/redirect', async (req, reply) => reply.redirect('/hello'));
app.get('/error', async () => { throw new Error('boom'); });
app.get('/stream', async (req, reply) => {
  reply.type('text/plain');
  return Readable.from((async function* chunks() {
    for (let i = 0; i < 5; i++) {
      yield `chunk ${i}\n`;
      await new Promise((resolve) => setTimeout(resolve, 10));
    }
  })());
});
app.post('/upload', async (req) => {
  let sum = 0;
  for (const b of req.body) sum = (sum + b) % 65521;
  return { bytes: req.body.length, sum };
});
app.get('/slow', (req, reply) => {
  reply.hijack();
  reply.raw.write('start\n');
  reply.raw.on('close', () => events.push(`slow closed, finished: ${reply.raw.writableFinished}`));
});
app.get('/events', async () => events);

await app.listen({ port: Number(process.env.PORT ?? 3000) });
process.on('SIGTERM', () => app.close());
