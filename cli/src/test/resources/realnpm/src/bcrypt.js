import bcrypt from 'bcrypt';
export const run = () => bcrypt.hashSync('x', 4).length;
