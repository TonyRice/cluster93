// ioredis against a real Redis (D-116): strings, numbers, hashes, lists, expiry, pipelines, transactions, pub/sub and
// errors. TEST_REDIS is the server. Runs the same under Node.
import Redis from 'ioredis';

export async function run() {
  const out = {};
  const redis = new Redis(process.env.TEST_REDIS);
  await redis.flushdb();
  out.set = await redis.set('greeting', 'hello');
  out.get = await redis.get('greeting');
  out.incr = [await redis.incr('counter'), await redis.incrby('counter', 5)];
  await redis.hset('user:1', { name: 'ada', role: 'admin' });
  out.hash = await redis.hgetall('user:1');
  await redis.rpush('queue', 'a', 'b', 'c');
  out.list = [await redis.lpop('queue'), await redis.lrange('queue', 0, -1)];
  await redis.set('temp', 'x', 'EX', 100);
  out.ttl = (await redis.ttl('temp')) > 90;
  out.pipeline = await redis.pipeline().set('p1', '1').incr('p1').get('p1').exec();
  out.multi = await redis.multi().set('m1', 'a').append('m1', 'b').get('m1').exec();
  out.wrongType = await redis.lpush('greeting', 'x').then(() => 'ok', (e) => [e.name, e.message]);
  out.binary = (await redis.getBuffer('greeting')).toString('hex');
  out.keys = (await redis.keys('*')).sort();

  const subscriber = new Redis(process.env.TEST_REDIS);
  const received = new Promise((resolve) => subscriber.on('message', (channel, message) => resolve([channel, message])));
  await subscriber.subscribe('news');
  out.subscribers = await redis.publish('news', 'hello subscribers');
  out.message = await received;
  subscriber.disconnect();
  redis.disconnect();
  return out;
}
