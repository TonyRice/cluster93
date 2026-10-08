import { load, dump } from 'js-yaml';
export const run = () => ({ loaded: load('n: 3\nlist: [a, b]\nnested: {k: v}'), dumped: dump({ k: [1, 2] }) });
