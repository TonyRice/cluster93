// Express 5 as a service (D-123): it only listens, with no default export. RealNpmPackagesTest sends the same requests
// to it under real Node and through the cluster's ingress, and compares the answers.
import express from 'express';

const app = express();
const events = [];
app.use(express.json());
app.use(express.urlencoded({ extended: false }));
app.get('/hello', (req, res) => res.send('hello'));
app.get('/json', (req, res) => res.json({ hello: 'world', n: 1 }));
app.get('/users/:id', (req, res) => res.json({ id: req.params.id, expand: req.query.expand ?? null }));
app.post('/echo', (req, res) => res.json({ received: req.body }));
app.post('/form', (req, res) => res.json(req.body));
app.get('/cookies', (req, res) => {
  res.cookie('a', '1');
  res.cookie('b', '2', { httpOnly: true });
  res.send('cookies');
});
app.get('/redirect', (req, res) => res.redirect('/hello'));
app.get('/error', () => { throw new Error('boom'); });
app.get('/stream', async (req, res) => {
  res.type('text/plain');
  for (let i = 0; i < 5; i++) {
    res.write(`chunk ${i}\n`);
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
  res.end();
});
app.post('/upload', (req, res) => {
  let bytes = 0;
  let sum = 0;
  req.on('data', (chunk) => { for (const b of chunk) sum = (sum + b) % 65521; bytes += chunk.length; });
  req.on('end', () => res.json({ bytes, sum }));
});
app.get('/slow', (req, res) => {
  res.write('start\n');
  res.on('close', () => events.push(`slow closed, finished: ${res.writableFinished}`));
});
app.get('/events', (req, res) => res.json(events));
app.use((err, req, res, next) => res.status(500).json({ error: err.message }));

const server = app.listen(Number(process.env.PORT ?? 3000));
process.on('SIGTERM', () => server.close());
