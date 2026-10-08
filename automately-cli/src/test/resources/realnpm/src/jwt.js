import jwt from 'jsonwebtoken';
export const run = () => ({ token: jwt.sign({ a: 1 }, 'secret', { noTimestamp: true }) });
