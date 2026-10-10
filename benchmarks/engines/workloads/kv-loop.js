// Host-heavy: kv sets and gets, each a call into the host and a promise back (in-process stores in the worker).
const store = kv.namespace('bench');
async function iteration(i) {
  for (let k = 0; k < 1000; k++) {
    await store.set(`key-${k}`, { i, k, label: 'value-' + k });
    const value = await store.get(`key-${k}`);
    if (value.k !== k) throw new Error('kv mismatch');
  }
}
