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
// UTF-8 as the WHATWG Encoding standard decodes it (as Node and browsers do): a sequence that's broken or overlong
// becomes one U+FFFD and the byte that broke it is read again, so nothing after it is swallowed (D-103).
const utf8Decode = (input, fatal = false) => {
  const bytes = input instanceof Uint8Array ? input
    : ArrayBuffer.isView(input) ? new Uint8Array(input.buffer, input.byteOffset, input.byteLength) : new Uint8Array(input);
  let out = '';
  const units = [];
  const push = (c) => {
    if (c > 0xffff) {
      c -= 0x10000;
      units.push(0xd800 + (c >> 10), 0xdc00 + (c & 0x3ff));
    } else {
      units.push(c);
    }
    if (units.length >= 8192) {
      out += String.fromCharCode.apply(null, units);
      units.length = 0;
    }
  };
  // With fatal (D-125), a broken sequence throws, as Node's TextDecoder({ fatal: true }) does.
  const bad = () => {
    if (fatal) {
      throw Object.assign(new TypeError('The encoded data was not valid for encoding utf-8'), { code: 'ERR_ENCODING_INVALID_ENCODED_DATA' });
    }
    push(0xfffd);
  };
  let needed = 0, seen = 0, cp = 0, lower = 0x80, upper = 0xbf;
  for (let i = 0; i < bytes.length; i++) {
    const b = bytes[i];
    if (needed === 0) {
      if (b <= 0x7f) push(b);
      else if (b >= 0xc2 && b <= 0xdf) { needed = 1; cp = b & 0x1f; }
      else if (b >= 0xe0 && b <= 0xef) { if (b === 0xe0) lower = 0xa0; if (b === 0xed) upper = 0x9f; needed = 2; cp = b & 0x0f; }
      else if (b >= 0xf0 && b <= 0xf4) { if (b === 0xf0) lower = 0x90; if (b === 0xf4) upper = 0x8f; needed = 3; cp = b & 0x07; }
      else bad();
      continue;
    }
    if (b < lower || b > upper) {
      needed = seen = cp = 0;
      lower = 0x80;
      upper = 0xbf;
      bad();
      i--;
      continue;
    }
    lower = 0x80;
    upper = 0xbf;
    cp = (cp << 6) | (b & 0x3f);
    if (++seen === needed) {
      push(cp);
      needed = seen = cp = 0;
    }
  }
  if (needed) bad();
  return out + String.fromCharCode.apply(null, units);
};
define('TextEncoder', class TextEncoder { get encoding() { return 'utf-8'; } encode(text = '') { return utf8Encode(text); } });
define('TextDecoder', class TextDecoder {
  #fatal;
  #ignoreBOM;
  constructor(label = 'utf-8', options = {}) {
    this.#fatal = Boolean(options?.fatal);
    this.#ignoreBOM = Boolean(options?.ignoreBOM);
  }
  get encoding() { return 'utf-8'; }
  get fatal() { return this.#fatal; }
  get ignoreBOM() { return this.#ignoreBOM; }
  decode(bytes) { return bytes ? utf8Decode(bytes, this.#fatal) : ''; }
});

const headerBag = (raw) => {
  const lower = {};
  for (const [k, v] of Object.entries(raw ?? {})) lower[String(k).toLowerCase()] = String(v);
  return Object.freeze({ ...lower, get: (name) => lower[String(name).toLowerCase()] ?? null });
};
