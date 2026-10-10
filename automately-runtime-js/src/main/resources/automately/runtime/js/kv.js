// kv: KvBinding over the tenant's key-value store, one frozen object per namespace.
const kvNamespace = (ns) => Object.freeze({
  get: (key) => host.kv.get(ns, key),
  set: (key, value, options) => host.kv.set(ns, key, value, options),
  delete: (key) => host.kv.delete(ns, key),
  list: (options) => host.kv.list(ns, options),
  increment: (key, by) => host.kv.increment(ns, key, by),
});
define('kv', Object.freeze({ ...kvNamespace('default'), namespace: (name) => kvNamespace(String(name)) }));
