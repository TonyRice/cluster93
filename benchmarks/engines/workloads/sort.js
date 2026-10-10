// Pure computation: sorting numbers and objects.
let numbers;
let records;
function setup() {
  let seed = 7;
  const next = () => (seed = (seed * 1103515245 + 12345) % 2147483648) / 2147483648;
  numbers = Array.from({ length: 100000 }, next);
  records = Array.from({ length: 30000 }, (_, k) => ({ id: k, score: next(), name: 'n' + Math.floor(next() * 1e6) }));
}
function iteration() {
  const a = numbers.slice().sort((x, y) => x - y);
  const b = records.slice().sort((x, y) => x.score - y.score || x.name.localeCompare(y.name));
  return a[0] + b[0].id;
}
