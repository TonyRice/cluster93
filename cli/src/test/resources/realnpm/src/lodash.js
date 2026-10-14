import { chunk, groupBy, sortBy, debounce, cloneDeep, template, kebabCase } from 'lodash-es';
export const run = () => {
  const compiled = template('hello <%= user %>!');
  return { chunk: chunk([1, 2, 3, 4, 5], 2), groups: groupBy(['one', 'two', 'three'], 'length'), sorted: sortBy([{ n: 3 }, { n: 1 }], 'n').map(o => o.n),
    deep: cloneDeep({ a: { b: [1] } }), template: compiled({ user: 'tony' }), kebab: kebabCase('Hello World'), debounce: typeof debounce(() => 1, 10) };
};
