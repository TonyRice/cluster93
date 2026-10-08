import { produce } from 'immer';
export const run = () => {
  const base = { todos: [{ text: 'a', done: false }] };
  const next = produce(base, draft => { draft.todos[0].done = true; draft.todos.push({ text: 'b', done: false }); });
  return { before: base.todos.length, after: next.todos.length, done: next.todos[0].done, frozen: Object.isFrozen(next) };
};
