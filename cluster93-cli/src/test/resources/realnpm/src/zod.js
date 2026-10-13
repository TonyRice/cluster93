import { z } from 'zod';
const User = z.object({ name: z.string().min(2), age: z.number().int().positive(), email: z.string().email() });
export const run = () => {
  const good = User.safeParse({ name: 'Tony', age: 40, email: 'a@b.co' });
  const bad = User.safeParse({ name: 'T', age: -1, email: 'nope' });
  return { good: good.success, badFields: bad.success ? [] : bad.error.issues.map(i => i.path.join('.')).sort() };
};
