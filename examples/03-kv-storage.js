// 03 — Key-value storage.
//
// Run it:
//   automately run examples/03-kv-storage.js
//
// kv is the tenant's JSON key-value store: replicated, durable, and shared with every node. See
// docs/scripting/host-apis.md.
export default async (input, ctx) => {
  await kv.set('counter', 1);
  const initial = await kv.get('counter');

  // increment is atomic: safe under concurrent jobs.
  const afterIncrement = await kv.increment('counter');

  // A namespace is the same API over a separate key space.
  const test = kv.namespace('test');
  await test.set('hello', { from: 'namespace' });
  const namespaced = await test.get('hello');

  log.info('kv results', { initial, afterIncrement, namespaced });
  return { initial, afterIncrement, namespaced };
};
