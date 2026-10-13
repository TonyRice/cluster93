// A stand-in for Node's `assert` (D-103), pure JavaScript. The comparisons follow Node's rules (strict: Object.is,
// prototypes and own symbols; loose: ==); failure messages are Node's for simple values and shorter for structures.
import { inspect } from 'node:util';

export class AssertionError extends Error {
  constructor(options = {}) {
    const { message, actual, expected, operator } = options;
    super(message !== undefined ? String(message) : describe(actual, expected, operator));
    this.name = 'AssertionError';
    this.code = 'ERR_ASSERTION';
    this.actual = actual;
    this.expected = expected;
    this.operator = operator;
    this.generatedMessage = message === undefined;
  }
}

const show = (value) => inspect(value, { depth: 3 });

function describe(actual, expected, operator) {
  switch (operator) {
    case 'strictEqual':
      return 'Expected values to be strictly equal:\n\n' + show(actual) + ' !== ' + show(expected) + '\n';
    case 'notStrictEqual':
      return 'Expected "actual" to be strictly unequal to: ' + show(expected);
    case 'deepStrictEqual':
      return 'Expected values to be strictly deep-equal:\n' + show(actual) + '\n\nshould equal\n\n' + show(expected);
    case 'notDeepStrictEqual':
      return 'Expected "actual" not to be strictly deep-equal to: ' + show(expected);
    case '==':
      return show(actual) + ' == ' + show(expected);
    case '!=':
      return show(actual) + ' != ' + show(expected);
    case 'deepEqual':
      return 'Expected values to be loosely deep-equal:\n\n' + show(actual) + '\n\nshould loosely deep-equal\n\n' + show(expected);
    case 'notDeepEqual':
      return '"actual" deepEqual "expected"';
    default:
      return 'Failed';
  }
}

function raise(message, actual, expected, operator, stackStart) {
  if (message instanceof Error) throw message;
  const error = new AssertionError({ message, actual, expected, operator });
  if (Error.captureStackTrace) Error.captureStackTrace(error, stackStart);
  throw error;
}

// --- deep equality ---

const tag = (value) => Object.prototype.toString.call(value);
const isPrimitive = (value) => value === null || (typeof value !== 'object' && typeof value !== 'function');

function ownKeys(object, strict) {
  const keys = Object.keys(object);
  if (strict) {
    for (const symbol of Object.getOwnPropertySymbols(object)) {
      if (Object.prototype.propertyIsEnumerable.call(object, symbol)) keys.push(symbol);
    }
  }
  return keys;
}

function deepEqual(a, b, strict, seen) {
  if (strict ? Object.is(a, b) : a == b || (a !== a && b !== b)) return true; // eslint-disable-line eqeqeq
  if (isPrimitive(a) || isPrimitive(b)) {
    // Loosely, a primitive equals an object only by ==, already checked.
    return false;
  }
  if (strict && Object.getPrototypeOf(a) !== Object.getPrototypeOf(b)) return false;
  const kind = tag(a);
  if (kind !== tag(b)) return false;
  if (Array.isArray(a) !== Array.isArray(b)) return false;

  const pair = seen.get(a);
  if (pair !== undefined && pair === b) return true;
  seen.set(a, b);

  if (a instanceof Date) {
    if (a.getTime() !== b.getTime()) return false;
  } else if (a instanceof RegExp) {
    if (a.source !== b.source || a.flags !== b.flags || a.lastIndex !== b.lastIndex) return false;
  } else if (a instanceof Error) {
    if (a.message !== b.message || a.name !== b.name) return false;
  } else if (ArrayBuffer.isView(a)) {
    if (a.byteLength !== b.byteLength) return false;
    if (!strict && !(a instanceof DataView)) {
      for (let i = 0; i < a.length; i++) if (a[i] != b[i]) return false; // eslint-disable-line eqeqeq
    } else {
      const x = new Uint8Array(a.buffer, a.byteOffset, a.byteLength);
      const y = new Uint8Array(b.buffer, b.byteOffset, b.byteLength);
      for (let i = 0; i < x.length; i++) if (x[i] !== y[i]) return false;
    }
    // Their own keys beyond the indices still count.
    const ka = ownKeys(a, strict).filter((k) => typeof k !== 'string' || !/^\d+$/.test(k));
    const kb = ownKeys(b, strict).filter((k) => typeof k !== 'string' || !/^\d+$/.test(k));
    return sameKeys(a, b, ka, kb, strict, seen);
  } else if (a instanceof ArrayBuffer || (typeof SharedArrayBuffer !== 'undefined' && a instanceof SharedArrayBuffer)) {
    if (a.byteLength !== b.byteLength) return false;
    const x = new Uint8Array(a);
    const y = new Uint8Array(b);
    for (let i = 0; i < x.length; i++) if (x[i] !== y[i]) return false;
  } else if (kind === '[object Number]' || kind === '[object String]' || kind === '[object Boolean]' || kind === '[object BigInt]'
      || kind === '[object Symbol]') {
    if (!Object.is(a.valueOf(), b.valueOf())) return false;
  }

  if (a instanceof Set) {
    if (a.size !== b.size || !setEqual(a, b, strict, seen)) return false;
  } else if (a instanceof Map) {
    if (a.size !== b.size || !mapEqual(a, b, strict, seen)) return false;
  }
  return sameKeys(a, b, ownKeys(a, strict), ownKeys(b, strict), strict, seen);
}

function sameKeys(a, b, ka, kb, strict, seen) {
  if (ka.length !== kb.length) return false;
  for (const key of ka) {
    if (!Object.prototype.propertyIsEnumerable.call(b, key)) return false;
  }
  for (const key of ka) {
    if (!deepEqual(a[key], b[key], strict, seen)) return false;
  }
  return true;
}

function setEqual(a, b, strict, seen) {
  const objects = [];
  for (const value of a) {
    if (isPrimitive(value) ? b.has(value) : false) continue;
    if (!isPrimitive(value)) {
      objects.push(value);
    } else if (strict) {
      return false;
    } else {
      // Loosely, 1 matches '1'.
      let found = false;
      for (const other of b) if (isPrimitive(other) && other == value) { found = true; break; } // eslint-disable-line eqeqeq
      if (!found) return false;
    }
  }
  if (objects.length === 0) return true;
  const candidates = [...b].filter((value) => !isPrimitive(value));
  for (const value of objects) {
    const at = candidates.findIndex((other) => deepEqual(value, other, strict, new Map(seen)));
    if (at < 0) return false;
    candidates.splice(at, 1);
  }
  return true;
}

function mapEqual(a, b, strict, seen) {
  const pending = [];
  for (const [key, value] of a) {
    if (isPrimitive(key) && b.has(key)) {
      if (!deepEqual(value, b.get(key), strict, seen)) return false;
    } else {
      pending.push([key, value]);
    }
  }
  if (pending.length === 0) return true;
  const candidates = [...b].filter(([key]) => !(isPrimitive(key) && a.has(key)));
  for (const [key, value] of pending) {
    const at = candidates.findIndex(([k, v]) => deepEqual(key, k, strict, new Map(seen)) && deepEqual(value, v, strict, new Map(seen)));
    if (at < 0) return false;
    candidates.splice(at, 1);
  }
  return true;
}

const isDeepStrictEqual = (a, b) => deepEqual(a, b, true, new Map());

// --- the assertions ---

export function ok(value, message) {
  if (!value) raise(message ?? (arguments.length === 0 ? 'No value argument passed to `assert.ok()`'
    : 'The expression evaluated to a falsy value:\n\n  assert.ok(' + show(value) + ')\n'), value, true, '==', ok);
}

export function equal(actual, expected, message) {
  // eslint-disable-next-line eqeqeq
  if (!(actual == expected || (actual !== actual && expected !== expected))) raise(message, actual, expected, '==', equal);
}

export function notEqual(actual, expected, message) {
  // eslint-disable-next-line eqeqeq
  if (actual == expected || (actual !== actual && expected !== expected)) raise(message, actual, expected, '!=', notEqual);
}

export function strictEqual(actual, expected, message) {
  if (!Object.is(actual, expected)) raise(message, actual, expected, 'strictEqual', strictEqual);
}

export function notStrictEqual(actual, expected, message) {
  if (Object.is(actual, expected)) raise(message, actual, expected, 'notStrictEqual', notStrictEqual);
}

function deepEqualAssert(actual, expected, message) {
  if (!deepEqual(actual, expected, false, new Map())) raise(message, actual, expected, 'deepEqual', deepEqualAssert);
}

export function notDeepEqual(actual, expected, message) {
  if (deepEqual(actual, expected, false, new Map())) raise(message, actual, expected, 'notDeepEqual', notDeepEqual);
}

export function deepStrictEqual(actual, expected, message) {
  if (!isDeepStrictEqual(actual, expected)) raise(message, actual, expected, 'deepStrictEqual', deepStrictEqual);
}

export function notDeepStrictEqual(actual, expected, message) {
  if (isDeepStrictEqual(actual, expected)) raise(message, actual, expected, 'notDeepStrictEqual', notDeepStrictEqual);
}

export function fail(message = 'Failed') {
  raise(message, undefined, undefined, 'fail', fail);
}

export function ifError(value) {
  if (value !== null && value !== undefined) {
    const error = new AssertionError({
      message: 'ifError got unwanted exception: ' + (value && value.message !== undefined ? value.message : show(value)),
      actual: value, expected: null, operator: 'ifError',
    });
    error.generatedMessage = false;
    throw error;
  }
}

export function match(string, regexp, message) {
  if (typeof string !== 'string' || !regexp.test(string)) {
    raise(message ?? (typeof string !== 'string'
      ? 'The "string" argument must be of type string. Received type ' + typeof string + ' (' + show(string) + ')'
      : 'The input did not match the regular expression ' + String(regexp) + '. Input:\n\n' + show(string) + '\n'), string, regexp, 'match', match);
  }
}

export function doesNotMatch(string, regexp, message) {
  if (typeof string !== 'string' || regexp.test(string)) {
    raise(message ?? 'The input was expected to not match the regular expression ' + String(regexp) + '. Input:\n\n' + show(string) + '\n',
      string, regexp, 'doesNotMatch', doesNotMatch);
  }
}

const NO_ERROR = Symbol('no error');

/** Whether the thrown value meets throws()'s {@code expected}: a class, a RegExp, a validation function or an object. */
function matches(actual, expected, fn) {
  if (expected === undefined) return true;
  if (expected instanceof RegExp) return expected.test(String(actual));
  if (typeof expected === 'function') {
    if (expected.prototype !== undefined && actual instanceof expected) return true;
    if (Error.isPrototypeOf(expected) || expected === Error) return false;
    const result = expected.call({}, actual);
    if (result !== true) {
      raise(undefined, actual, expected, fn.name, fn);
    }
    return true;
  }
  if (typeof expected === 'object' && expected !== null) {
    for (const key of Object.keys(expected)) {
      const want = expected[key];
      const got = actual?.[key];
      if (want instanceof RegExp && typeof got === 'string' ? !want.test(got) : !isDeepStrictEqual(got, want)) return false;
    }
    return true;
  }
  return true;
}

function caught(fn) {
  try {
    fn();
  } catch (err) {
    return err;
  }
  return NO_ERROR;
}

export function throws(fn, expected, message) {
  if (typeof fn !== 'function') throw Object.assign(new TypeError('The "fn" argument must be of type function'), { code: 'ERR_INVALID_ARG_TYPE' });
  if (typeof expected === 'string') {
    message = expected;
    expected = undefined;
  }
  const error = caught(fn);
  if (error === NO_ERROR) {
    raise(message ?? 'Missing expected exception' + (expected?.name ? ' (' + expected.name + ').' : '.'), undefined, expected, 'throws', throws);
  }
  if (!matches(error, expected, throws)) {
    if (typeof expected === 'function' && !(expected.prototype !== undefined && error instanceof expected)) throw error;
    raise(message ?? 'The error did not match the expected value', error, expected, 'throws', throws);
  }
}

export function doesNotThrow(fn, expected, message) {
  if (typeof expected === 'string') {
    message = expected;
    expected = undefined;
  }
  const error = caught(fn);
  if (error === NO_ERROR) return;
  if (expected === undefined || matches(error, expected, doesNotThrow)) {
    raise('Got unwanted exception' + (message ? ': ' + message : '.') + '\nActual message: "' + (error?.message ?? String(error)) + '"',
      error, expected, 'doesNotThrow', doesNotThrow);
  }
  throw error;
}

async function settle(promiseOrFn) {
  const promise = typeof promiseOrFn === 'function' ? promiseOrFn() : promiseOrFn;
  if (!promise || typeof promise.then !== 'function') {
    throw Object.assign(new TypeError('The "promiseFn" argument must return a Promise'), { code: 'ERR_INVALID_RETURN_VALUE' });
  }
  try {
    await promise;
  } catch (err) {
    return err;
  }
  return NO_ERROR;
}

export async function rejects(promiseOrFn, expected, message) {
  if (typeof expected === 'string') {
    message = expected;
    expected = undefined;
  }
  const error = await settle(promiseOrFn);
  if (error === NO_ERROR) {
    raise(message ?? 'Missing expected rejection' + (expected?.name ? ' (' + expected.name + ').' : '.'), undefined, expected, 'rejects', rejects);
  }
  if (!matches(error, expected, rejects)) {
    if (typeof expected === 'function' && !(expected.prototype !== undefined && error instanceof expected)) throw error;
    raise(message ?? 'The error did not match the expected value', error, expected, 'rejects', rejects);
  }
}

export async function doesNotReject(promiseOrFn, expected, message) {
  if (typeof expected === 'string') {
    message = expected;
    expected = undefined;
  }
  const error = await settle(promiseOrFn);
  if (error === NO_ERROR) return;
  raise('Got unwanted rejection' + (message ? ': ' + message : '.') + '\nActual message: "' + (error?.message ?? String(error)) + '"',
    error, expected, 'doesNotReject', doesNotReject);
}

function assert(value, message) {
  ok(value, message);
}

const strict = Object.assign((value, message) => ok(value, message), {
  ok, fail, ifError, match, doesNotMatch, throws, doesNotThrow, rejects, doesNotReject, AssertionError,
  equal: strictEqual, notEqual: notStrictEqual, deepEqual: deepStrictEqual, notDeepEqual: notDeepStrictEqual,
  strictEqual, notStrictEqual, deepStrictEqual, notDeepStrictEqual,
});
strict.strict = strict;

Object.assign(assert, {
  ok, fail, ifError, match, doesNotMatch, throws, doesNotThrow, rejects, doesNotReject, AssertionError,
  equal, notEqual, deepEqual: deepEqualAssert, notDeepEqual, strictEqual, notStrictEqual, deepStrictEqual, notDeepStrictEqual, strict,
});

export { strict, deepEqualAssert as deepEqual };
export default assert;
