// Koa with @koa/router and @koa/bodyparser as a service (D-123): it only listens. RealNpmPackagesTest sends the same
// requests to it under real Node and through the cluster's ingress, and compares the answers.
import Koa from 'koa';
import { Router } from '@koa/router';
import { bodyParser } from '@koa/bodyparser';
import { Readable } from 'node:stream';

const app = new Koa();
const router = new Router();
const events = [];
app.use(async (ctx, next) => {
  try {
    await next();
  } catch (e) {
    ctx.status = 500;
    ctx.body = { error: e.message };
  }
});
app.use(bodyParser());
router.get('/hello', (ctx) => { ctx.body = 'hello'; });
router.get('/json', (ctx) => { ctx.body = { hello: 'world', n: 1 }; });
router.get('/users/:id', (ctx) => { ctx.body = { id: ctx.params.id, expand: ctx.query.expand ?? null }; });
router.post('/echo', (ctx) => { ctx.body = { received: ctx.request.body }; });
router.post('/form', (ctx) => { ctx.body = ctx.request.body; });
router.get('/cookies', (ctx) => {
  ctx.cookies.set('a', '1', { httpOnly: false });
  ctx.cookies.set('b', '2');
  ctx.body = 'cookies';
});
router.get('/redirect', (ctx) => ctx.redirect('/hello'));
router.get('/error', () => { throw new Error('boom'); });
router.get('/stream', (ctx) => {
  ctx.type = 'text/plain';
  ctx.body = Readable.from((async function* chunks() {
    for (let i = 0; i < 5; i++) {
      yield `chunk ${i}\n`;
      await new Promise((resolve) => setTimeout(resolve, 10));
    }
  })());
});
router.post('/upload', async (ctx) => {
  let bytes = 0;
  let sum = 0;
  for await (const chunk of ctx.req) { for (const b of chunk) sum = (sum + b) % 65521; bytes += chunk.length; }
  ctx.body = { bytes, sum };
});
router.get('/slow', (ctx) => {
  ctx.respond = false;
  ctx.res.write('start\n');
  ctx.res.on('close', () => events.push(`slow closed, finished: ${ctx.res.writableFinished}`));
});
router.get('/events', (ctx) => { ctx.body = events; });
app.use(router.routes()).use(router.allowedMethods());

const server = app.listen(Number(process.env.PORT ?? 3000));
process.on('SIGTERM', () => server.close());
