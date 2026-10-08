// A stand-in for Node's `querystring` (D-103), pure JavaScript, following Node's rules: '+' is a space, a repeated key
// becomes an array, a key without '=' has the value '', at most 1000 keys unless maxKeys says otherwise.
import { Buffer } from 'node:buffer';

export function unescape(text) {
  try {
    return decodeURIComponent(text);
  } catch {
    // As Node does: the escapes that are whole become bytes, the rest stay as text, and it's all read as UTF-8.
    const bytes = [];
    const utf8 = Buffer.from(text);
    for (let i = 0; i < utf8.length; i++) {
      const hex = utf8[i] === 0x25 && i + 2 < utf8.length + 0 ? String.fromCharCode(utf8[i + 1], utf8[i + 2]) : '';
      if (/^[0-9a-fA-F]{2}$/.test(hex)) {
        bytes.push(parseInt(hex, 16));
        i += 2;
      } else {
        bytes.push(utf8[i]);
      }
    }
    return Buffer.from(bytes).toString('utf8');
  }
}

export function escape(text) {
  return encodeURIComponent(String(text));
}

function primitive(value) {
  switch (typeof value) {
    case 'string':
      return value;
    case 'number':
      return Number.isFinite(value) ? String(value) : '';
    case 'bigint':
    case 'boolean':
      return String(value);
    default:
      return '';
  }
}

export function stringify(object, sep, eq, options) {
  sep ||= '&';
  eq ||= '=';
  const encode = options?.encodeURIComponent ?? escape;
  if (object === null || typeof object !== 'object') return '';
  const parts = [];
  for (const key of Object.keys(object)) {
    const value = object[key];
    const name = encode(primitive(key)) + eq;
    if (Array.isArray(value)) {
      for (const item of value) parts.push(name + encode(primitive(item)));
    } else {
      parts.push(name + encode(primitive(value)));
    }
  }
  return parts.join(sep);
}

export function parse(text, sep, eq, options) {
  sep ||= '&';
  eq ||= '=';
  const result = Object.create(null);
  if (typeof text !== 'string' || text.length === 0) return result;
  const decode = options?.decodeURIComponent ?? unescape;
  const maxKeys = options?.maxKeys ?? 1000;
  let pairs = text.split(sep);
  if (maxKeys > 0) pairs = pairs.slice(0, maxKeys);
  for (const pair of pairs) {
    if (pair.length === 0) continue;
    const at = pair.indexOf(eq);
    const rawKey = at >= 0 ? pair.slice(0, at) : pair;
    const rawValue = at >= 0 ? pair.slice(at + eq.length) : '';
    const key = decode(rawKey.replace(/\+/g, ' '));
    const value = decode(rawValue.replace(/\+/g, ' '));
    if (!(key in result)) {
      result[key] = value;
    } else if (Array.isArray(result[key])) {
      result[key].push(value);
    } else {
      result[key] = [result[key], value];
    }
  }
  return result;
}

export const decode = parse;
export const encode = stringify;
export default { parse, stringify, escape, unescape, decode, encode };
