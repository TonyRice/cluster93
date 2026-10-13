import { nanoid, customAlphabet } from 'nanoid';
export const run = () => ({ id: nanoid().length, custom: /^[ab]{10}$/.test(customAlphabet('ab', 10)()) });
