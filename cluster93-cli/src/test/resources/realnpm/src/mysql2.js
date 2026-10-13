// mysql2 against a real MySQL (D-116): the promise API, prepared statements, types, a transaction, errors and a pool.
// TEST_MYSQL is the database. Runs the same under Node.
import mysql from 'mysql2/promise';

const failure = (promise) => promise.then(() => 'ok', (e) => ({ code: e.code, errno: e.errno, sqlState: e.sqlState, message: e.message }));

export async function run() {
  const out = {};
  const db = await mysql.createConnection(process.env.TEST_MYSQL);
  await db.query('drop table if exists items');
  await db.query('create table items (id int auto_increment primary key, name varchar(50) not null unique, price decimal(10, 2), meta json, made date, ok boolean)');
  const [inserted] = await db.execute('insert into items (name, price, meta, made, ok) values (?, ?, ?, ?, ?), (?, ?, ?, ?, ?)',
    ['apple', 1.25, JSON.stringify({ origin: 'nz' }), '2026-01-02', true, 'bread', 3.5, JSON.stringify({ gluten: true }), '2026-02-03', false]);
  out.inserted = { affectedRows: inserted.affectedRows, insertId: inserted.insertId };
  const [rows, fields] = await db.query("select name, price, meta, date_format(made, '%Y-%m-%d') as made, ok from items order by id");
  out.rows = rows;
  out.fields = fields.map((f) => f.name);
  const [one] = await db.execute('select name from items where price > ?', [2]);
  out.prepared = one;
  await db.beginTransaction();
  await db.query("delete from items where name = 'apple'");
  await db.rollback();
  out.afterRollback = (await db.query('select count(*) as n from items'))[0][0].n;
  out.duplicate = await failure(db.execute('insert into items (name) values (?)', ['apple']));
  out.syntax = await failure(db.query('selec 1'));
  await db.end();

  const pool = mysql.createPool({ uri: process.env.TEST_MYSQL, connectionLimit: 3 });
  out.pool = (await Promise.all([1, 2, 3, 4, 5].map((i) => pool.query('select ? * 2 as v', [i])))).map(([r]) => r[0].v);
  await pool.end();
  return out;
}
