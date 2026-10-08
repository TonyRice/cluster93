// Node's `assert/strict` (D-103): assert in strict mode, where equal means strictEqual and deepEqual deepStrictEqual.
import assert from 'node:assert';

const strict = assert.strict;
export default strict;
export const {
  ok, fail, ifError, match, doesNotMatch, throws, doesNotThrow, rejects, doesNotReject, AssertionError, equal, notEqual, deepEqual,
  notDeepEqual, strictEqual, notStrictEqual, deepStrictEqual, notDeepStrictEqual,
} = strict;
export { strict };
