// pg against a real Postgres (D-116): queries, parameters and types, a transaction, errors, a pool, and TLS through
// Postgres's SSLRequest upgrade. TEST_PG is the database; TEST_PG_CA its certificate's CA. Runs the same under Node.
import pg from 'pg';

const failure = (promise) => promise.then(() => 'ok', (e) => ({ code: e.code, message: e.message }));

export async function run() {
  const out = {};
  const client = new pg.Client({ connectionString: process.env.TEST_PG });
  await client.connect();
  await client.query('drop table if exists items');
  await client.query('create table items (id serial primary key, name text not null unique, price numeric(10, 2), tags text[], meta jsonb, made date, ok boolean)');
  const inserted = await client.query(
    'insert into items (name, price, tags, meta, made, ok) values ($1, $2, $3, $4, $5, $6), ($7, $8, $9, $10, $11, $12) returning id, name',
    ['apple', 1.25, ['fruit', 'red'], { origin: 'nz' }, '2026-01-02', true, 'bread', 3.5, ['bakery'], { gluten: true }, '2026-02-03', false]);
  out.inserted = { rows: inserted.rows, rowCount: inserted.rowCount, command: inserted.command };
  const all = await client.query('select name, price, tags, meta, made::text as made, ok from items order by id');
  out.rows = all.rows;
  out.fields = all.fields.map((f) => [f.name, f.dataTypeID]);
  await client.query('begin');
  await client.query('delete from items where name = $1', ['apple']);
  await client.query('rollback');
  out.afterRollback = (await client.query('select count(*)::int as n from items')).rows[0].n;
  out.missingTable = await failure(client.query('select * from missing_table'));
  out.duplicate = await failure(client.query('insert into items (name) values ($1)', ['apple']));
  out.arrays = (await client.query({ text: 'select generate_series(1, 4) as n, $1::text as label', values: ['x'], rowMode: 'array' })).rows;
  out.big = (await client.query("select repeat('ab', 50000) as s")).rows[0].s.length;
  await client.end();

  const pool = new pg.Pool({ connectionString: process.env.TEST_PG, max: 3 });
  out.pool = (await Promise.all([1, 2, 3, 4, 5, 6].map((i) => pool.query('select $1::int * 2 as v', [i])))).map((r) => r.rows[0].v);
  await pool.end();

  // TLS: the client asks with an SSLRequest, then upgrades the same connection.
  const unverified = new pg.Client({ connectionString: process.env.TEST_PG, ssl: { rejectUnauthorized: false } });
  await unverified.connect();
  out.tls = (await unverified.query('select ssl from pg_stat_ssl where pid = pg_backend_pid()')).rows[0];
  await unverified.end();
  const trusted = new pg.Client({ connectionString: process.env.TEST_PG, ssl: { ca: process.env.TEST_PG_CA, servername: 'localhost' } });
  await trusted.connect();
  out.tlsTrusted = (await trusted.query('select ssl from pg_stat_ssl where pid = pg_backend_pid()')).rows[0];
  await trusted.end();
  out.tlsWrongName = await failure(new pg.Client({ connectionString: process.env.TEST_PG, ssl: { ca: process.env.TEST_PG_CA } }).connect());
  return out;
}
