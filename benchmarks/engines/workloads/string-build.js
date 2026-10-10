// Pure computation: building strings by concatenation, templates and join.
function iteration(i) {
  let s = '';
  for (let k = 0; k < 100000; k++) s += k.toString(36);
  const parts = [];
  for (let k = 0; k < 100000; k++) parts.push(`${k}:${i}:${s.length % 97}`);
  const joined = parts.join(',');
  return joined.length + s.toUpperCase().length + joined.split(',').length;
}
