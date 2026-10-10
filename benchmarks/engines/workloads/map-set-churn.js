// Pure computation: Map and Set inserts, lookups and deletes.
function iteration(i) {
  const map = new Map();
  const set = new Set();
  for (let k = 0; k < 100000; k++) {
    map.set('k' + k, k);
    set.add(k * 3);
  }
  let hits = 0;
  for (let k = 0; k < 100000; k++) {
    if (map.get('k' + (k ^ i)) !== undefined) hits++;
    if (set.has(k)) hits++;
  }
  for (let k = 0; k < 50000; k++) {
    map.delete('k' + k);
    set.delete(k * 3);
  }
  return hits + map.size + set.size;
}
