// Our polyfills: TextEncoder and TextDecoder over ~1 MB of mixed text.
let text;
const encoder = new TextEncoder();
const decoder = new TextDecoder();
function setup() {
  text = 'plain ascii, ünïcödé, 日本語のテキスト, emoji 🎉🚀 '.repeat(20000);
}
function iteration() {
  const bytes = encoder.encode(text);
  return decoder.decode(bytes).length + bytes.length;
}
