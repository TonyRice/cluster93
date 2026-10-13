import YAML from 'yaml';
export const run = () => ({ yaml: YAML.parse('a: 1\nb:\n  - x\n  - y\n'), stringified: YAML.stringify({ k: [1, 2] }) });
