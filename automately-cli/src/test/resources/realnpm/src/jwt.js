import jwt from 'jsonwebtoken';
export const run = () => {
  const token = jwt.sign({ a: 1 }, 'secret', { noTimestamp: true });
  let tampered;
  try { jwt.verify(token.slice(0, -2) + 'xx', 'secret'); } catch (e) { tampered = e.message; }
  return { token, verified: jwt.verify(token, 'secret'), tampered };
};
