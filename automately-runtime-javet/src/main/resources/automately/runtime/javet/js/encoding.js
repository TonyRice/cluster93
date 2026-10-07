// UTF-8 text <-> bytes (V8 itself has no TextEncoder/TextDecoder), and case-insensitive header bags.
const utf8Encode = (text) => {
  const out = [];
  for (const ch of String(text)) {
    let c = ch.codePointAt(0);
    if (c < 0x80) out.push(c);
    else if (c < 0x800) out.push(0xc0 | (c >> 6), 0x80 | (c & 63));
    else if (c < 0x10000) out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
    else out.push(0xf0 | (c >> 18), 0x80 | ((c >> 12) & 63), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
  }
  return new Uint8Array(out);
};
const utf8Decode = (bytes) => {
  let s = '';
  for (let i = 0; i < bytes.length;) {
    const b = bytes[i];
    let c, n;
    if (b < 0x80) { c = b; n = 1; }
    else if (b >= 0xf0) { c = ((b & 7) << 18) | ((bytes[i + 1] & 63) << 12) | ((bytes[i + 2] & 63) << 6) | (bytes[i + 3] & 63); n = 4; }
    else if (b >= 0xe0) { c = ((b & 15) << 12) | ((bytes[i + 1] & 63) << 6) | (bytes[i + 2] & 63); n = 3; }
    else if (b >= 0xc0) { c = ((b & 31) << 6) | (bytes[i + 1] & 63); n = 2; }
    else { c = 0xfffd; n = 1; }
    s += String.fromCodePoint(c);
    i += n;
  }
  return s;
};
define('TextEncoder', class TextEncoder { get encoding() { return 'utf-8'; } encode(text = '') { return utf8Encode(text); } });
define('TextDecoder', class TextDecoder { get encoding() { return 'utf-8'; } decode(bytes) { return bytes ? utf8Decode(bytes) : ''; } });

const headerBag = (raw) => {
  const lower = {};
  for (const [k, v] of Object.entries(raw ?? {})) lower[String(k).toLowerCase()] = String(v);
  return Object.freeze({ ...lower, get: (name) => lower[String(name).toLowerCase()] ?? null });
};
