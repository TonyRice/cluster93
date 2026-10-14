import { v4, v7, validate, version } from 'uuid';
export const run = () => {
  const a = v4();
  const b = v7();
  return { v4: validate(a) && version(a), v7: validate(b) && version(b), distinct: a !== v4() };
};
