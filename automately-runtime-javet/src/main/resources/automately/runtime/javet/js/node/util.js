// A stand-in for Node's `util` (D-089): the parts npm code commonly uses. `inspect` is a readable approximation of
// Node's output, not a byte-for-byte copy.

const customInspect = Symbol.for('nodejs.util.inspect.custom');
const customPromisify = Symbol.for('nodejs.util.promisify.custom');

function quote(s) {
  return "'" + s.replace(/\\/g, '\\\\').replace(/'/g, "\\'").replace(/\n/g, '\\n') + "'";
}

function key(k) {
  if (typeof k === 'symbol') {
    return '[' + k.toString() + ']';
  }
  return /^[A-Za-z_$][\w$]*$/.test(k) ? k : quote(k);
}

function format1(value, depth, opts, seen) {
  switch (typeof value) {
    case 'string':
      return depth === 0 && opts.top ? value : quote(value);
    case 'number':
      return Object.is(value, -0) ? '-0' : String(value);
    case 'bigint':
      return value + 'n';
    case 'boolean':
    case 'undefined':
      return String(value);
    case 'symbol':
      return value.toString();
    case 'function': {
      const name = value.name ? ': ' + value.name : ' (anonymous)';
      return /^class\s/.test(Function.prototype.toString.call(value)) ? '[class' + (value.name ? ' ' + value.name : ' (anonymous)') + ']' : '[Function' + name + ']';
    }
  }
  if (value === null) {
    return 'null';
  }
  if (typeof value[customInspect] === 'function' && value[customInspect] !== inspect) {
    const custom = value[customInspect](opts.depth - depth, opts, inspect);
    return typeof custom === 'string' ? custom : format1(custom, depth, opts, seen);
  }
  if (seen.includes(value)) {
    return '[Circular]';
  }
  if (value instanceof Date) {
    return Number.isNaN(value.getTime()) ? 'Invalid Date' : value.toISOString();
  }
  if (value instanceof RegExp) {
    return String(value);
  }
  if (value instanceof Error) {
    return value.stack ? String(value.stack) : '[' + String(value) + ']';
  }
  if (typeof Promise !== 'undefined' && value instanceof Promise) {
    return 'Promise { <unknown> }';
  }
  if (depth > opts.depth) {
    return Array.isArray(value) ? '[Array]' : '[Object]';
  }
  const inner = seen.concat([value]);
  const show = (v) => format1(v, depth + 1, opts, inner);
  let open;
  let close;
  let items;
  if (Array.isArray(value)) {
    open = '[';
    close = ']';
    items = value.slice(0, 100).map(show);
    if (value.length > 100) {
      items.push('... ' + (value.length - 100) + ' more items');
    }
  } else if (ArrayBuffer.isView(value) && !(value instanceof DataView)) {
    const name = value.constructor && value.constructor.name ? value.constructor.name : 'TypedArray';
    if (name === 'Buffer') {
      const hex = Array.from(value.subarray(0, 50), (b) => b.toString(16).padStart(2, '0'));
      return '<Buffer' + (hex.length ? ' ' + hex.join(' ') : '') + (value.length > 50 ? ' ... ' + (value.length - 50) + ' more bytes' : '') + '>';
    }
    open = name + '(' + value.length + ') [';
    close = ']';
    items = Array.from(value.subarray(0, 100), (v) => String(v));
  } else if (value instanceof Map) {
    open = 'Map(' + value.size + ') {';
    close = '}';
    items = Array.from(value, ([k, v]) => show(k) + ' => ' + show(v));
  } else if (value instanceof Set) {
    open = 'Set(' + value.size + ') {';
    close = '}';
    items = Array.from(value, show);
  } else {
    const proto = Object.getPrototypeOf(value);
    const name = proto === null ? '[Object: null prototype] ' : proto !== Object.prototype && proto.constructor && proto.constructor.name ? proto.constructor.name + ' ' : '';
    open = name + '{';
    close = '}';
    items = [];
  }
  if (!Array.isArray(value) && !(value instanceof Map) && !(value instanceof Set) && !ArrayBuffer.isView(value)) {
    for (const k of Reflect.ownKeys(value)) {
      const desc = Object.getOwnPropertyDescriptor(value, k);
      if (!desc.enumerable) {
        continue;
      }
      items.push(key(k) + ': ' + ('value' in desc ? show(desc.value) : desc.get && desc.set ? '[Getter/Setter]' : desc.get ? '[Getter]' : '[Setter]'));
    }
  }
  if (items.length === 0) {
    return open.endsWith('[') ? open + ']' : open + '}';
  }
  const oneLine = open + ' ' + items.join(', ') + ' ' + close;
  if (oneLine.length <= 72 && !items.some((i) => i.includes('\n'))) {
    return oneLine;
  }
  const pad = '  '.repeat(depth + 1);
  return open + '\n' + items.map((i) => pad + i.replace(/\n/g, '\n' + pad)).join(',\n') + '\n' + '  '.repeat(depth) + close;
}

export function inspect(value, options = {}) {
  const opts = { depth: 2, ...(typeof options === 'object' && options !== null ? options : {}) };
  if (opts.depth === null) {
    opts.depth = Infinity;
  }
  return format1(value, 0, { ...opts, top: false }, []);
}
inspect.custom = customInspect;
inspect.defaultOptions = { depth: 2 };

export function format(first, ...args) {
  if (typeof first !== 'string') {
    return [first, ...args].map((a) => (typeof a === 'string' ? a : inspect(a))).join(' ');
  }
  let i = 0;
  let out = first.replace(/%[sdifjoOc%]/g, (token) => {
    if (token === '%%') {
      return '%';
    }
    if (i >= args.length) {
      return token;
    }
    const arg = args[i++];
    switch (token) {
      case '%s':
        return typeof arg === 'string' ? arg : typeof arg === 'bigint' ? arg + 'n' : typeof arg === 'object' && arg !== null ? inspect(arg, { depth: 0 }) : String(arg);
      case '%d':
        return typeof arg === 'bigint' ? arg + 'n' : typeof arg === 'symbol' ? 'NaN' : String(Number(arg));
      case '%i':
        return typeof arg === 'bigint' ? arg + 'n' : String(parseInt(arg, 10));
      case '%f':
        return String(parseFloat(arg));
      case '%j':
        try {
          return JSON.stringify(arg);
        } catch {
          return '[Circular]';
        }
      case '%o':
      case '%O':
        return inspect(arg, { depth: token === '%o' ? 4 : 2 });
      case '%c':
        return '';
    }
    return token;
  });
  for (; i < args.length; i++) {
    out += ' ' + (typeof args[i] === 'string' ? args[i] : inspect(args[i]));
  }
  return out;
}

export function formatWithOptions(options, ...args) {
  return format(...args);
}

export function promisify(original) {
  if (typeof original !== 'function') {
    throw new TypeError('The "original" argument must be of type function');
  }
  if (original[customPromisify]) {
    return original[customPromisify];
  }
  function promisified(...args) {
    return new Promise((resolve, reject) => {
      original.call(this, ...args, (err, ...values) => {
        if (err) {
          reject(err);
        } else {
          resolve(values.length > 1 ? values : values[0]);
        }
      });
    });
  }
  Object.setPrototypeOf(promisified, Object.getPrototypeOf(original));
  return Object.defineProperties(promisified, Object.getOwnPropertyDescriptors(original));
}
promisify.custom = customPromisify;

export function callbackify(original) {
  if (typeof original !== 'function') {
    throw new TypeError('The "original" argument must be of type function');
  }
  return function (...args) {
    const callback = args.pop();
    if (typeof callback !== 'function') {
      throw new TypeError('The last argument must be of type function');
    }
    // The callback runs outside the promise's handlers, so an exception it throws isn't turned into a rejection.
    const later = (fn) => Promise.resolve().then(fn);
    original.apply(this, args).then((value) => later(() => callback(null, value)),
      (err) => later(() => callback(err || Object.assign(new Error('Promise was rejected with a falsy value'), { reason: err }))));
  };
}

export function inherits(ctor, superCtor) {
  if (typeof ctor !== 'function' || typeof superCtor !== 'function' || superCtor.prototype === undefined) {
    throw new TypeError('inherits needs two constructors, the second with a prototype');
  }
  Object.defineProperty(ctor, 'super_', { value: superCtor, writable: true, configurable: true });
  Object.setPrototypeOf(ctor.prototype, superCtor.prototype);
}

export function deprecate(fn, message) {
  let warned = false;
  return function (...args) {
    if (!warned) {
      warned = true;
      console.warn('DeprecationWarning: ' + message);
    }
    return new.target ? Reflect.construct(fn, args, new.target) : fn.apply(this, args);
  };
}

export function debuglog() {
  const log = () => {};
  log.enabled = false;
  return log;
}
export const debug = debuglog;

export function isDeepStrictEqual(a, b) {
  return deepEqual(a, b, []);
}

function deepEqual(a, b, seen) {
  if (Object.is(a, b)) {
    return true;
  }
  if (typeof a !== 'object' || typeof b !== 'object' || a === null || b === null) {
    return false;
  }
  if (Object.getPrototypeOf(a) !== Object.getPrototypeOf(b)) {
    return false;
  }
  if (seen.some(([x, y]) => x === a && y === b)) {
    return true;
  }
  seen = seen.concat([[a, b]]);
  if (a instanceof Date) {
    return a.getTime() === b.getTime();
  }
  if (a instanceof RegExp) {
    return String(a) === String(b);
  }
  if (ArrayBuffer.isView(a)) {
    return a.length === b.length && a.every((v, i) => Object.is(v, b[i]));
  }
  if (a instanceof Map) {
    return a.size === b.size && Array.from(a).every(([k, v]) => b.has(k) && deepEqual(v, b.get(k), seen));
  }
  if (a instanceof Set) {
    return a.size === b.size && Array.from(a).every((v) => b.has(v));
  }
  const ka = Reflect.ownKeys(a).filter((k) => Object.prototype.propertyIsEnumerable.call(a, k));
  const kb = Reflect.ownKeys(b).filter((k) => Object.prototype.propertyIsEnumerable.call(b, k));
  return ka.length === kb.length && ka.every((k) => Object.prototype.hasOwnProperty.call(b, k) && deepEqual(a[k], b[k], seen));
}

const tag = (v) => Object.prototype.toString.call(v);
const typedArray = (name) => (v) => ArrayBuffer.isView(v) && tag(v) === '[object ' + name + ']';

export const types = {
  isDate: (v) => tag(v) === '[object Date]',
  isRegExp: (v) => tag(v) === '[object RegExp]',
  isPromise: (v) => tag(v) === '[object Promise]',
  isMap: (v) => tag(v) === '[object Map]',
  isSet: (v) => tag(v) === '[object Set]',
  isWeakMap: (v) => tag(v) === '[object WeakMap]',
  isWeakSet: (v) => tag(v) === '[object WeakSet]',
  isNativeError: (v) => v instanceof Error,
  isArrayBuffer: (v) => tag(v) === '[object ArrayBuffer]',
  isSharedArrayBuffer: (v) => tag(v) === '[object SharedArrayBuffer]',
  isAnyArrayBuffer: (v) => tag(v) === '[object ArrayBuffer]' || tag(v) === '[object SharedArrayBuffer]',
  isArrayBufferView: (v) => ArrayBuffer.isView(v),
  isDataView: (v) => tag(v) === '[object DataView]',
  isTypedArray: (v) => ArrayBuffer.isView(v) && tag(v) !== '[object DataView]',
  isUint8Array: typedArray('Uint8Array'),
  isUint8ClampedArray: typedArray('Uint8ClampedArray'),
  isUint16Array: typedArray('Uint16Array'),
  isUint32Array: typedArray('Uint32Array'),
  isInt8Array: typedArray('Int8Array'),
  isInt16Array: typedArray('Int16Array'),
  isInt32Array: typedArray('Int32Array'),
  isFloat32Array: typedArray('Float32Array'),
  isFloat64Array: typedArray('Float64Array'),
  isBigInt64Array: typedArray('BigInt64Array'),
  isBigUint64Array: typedArray('BigUint64Array'),
  isAsyncFunction: (v) => tag(v) === '[object AsyncFunction]' || tag(v) === '[object AsyncGeneratorFunction]',
  isGeneratorFunction: (v) => tag(v) === '[object GeneratorFunction]' || tag(v) === '[object AsyncGeneratorFunction]',
  isGeneratorObject: (v) => tag(v) === '[object Generator]',
  isBoxedPrimitive: (v) => ['[object Number]', '[object String]', '[object Boolean]', '[object BigInt]', '[object Symbol]'].includes(tag(v)) && typeof v === 'object',
  isSymbolObject: (v) => typeof v === 'object' && tag(v) === '[object Symbol]',
  isProxy: () => false,
};

export const isArray = Array.isArray;
export const isBoolean = (v) => typeof v === 'boolean';
export const isNull = (v) => v === null;
export const isNullOrUndefined = (v) => v === null || v === undefined;
export const isNumber = (v) => typeof v === 'number';
export const isString = (v) => typeof v === 'string';
export const isSymbol = (v) => typeof v === 'symbol';
export const isUndefined = (v) => v === undefined;
export const isRegExp = types.isRegExp;
export const isObject = (v) => v !== null && typeof v === 'object';
export const isDate = types.isDate;
export const isError = types.isNativeError;
export const isFunction = (v) => typeof v === 'function';
export const isPrimitive = (v) => v === null || (typeof v !== 'object' && typeof v !== 'function');

export const TextEncoder = globalThis.TextEncoder;
export const TextDecoder = globalThis.TextDecoder;

export function stripVTControlCharacters(s) {
  return String(s).replace(/\u001b\[[0-9;]*[A-Za-z]/g, '');
}

export default {
  inspect, format, formatWithOptions, promisify, callbackify, inherits, deprecate, debuglog, debug, isDeepStrictEqual, types,
  isArray, isBoolean, isNull, isNullOrUndefined, isNumber, isString, isSymbol, isUndefined, isRegExp, isObject, isDate, isError,
  isFunction, isPrimitive, TextEncoder, TextDecoder, stripVTControlCharacters,
};
