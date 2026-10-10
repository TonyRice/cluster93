// Pure computation: tokenizing ~2 MB of JavaScript-like text with a sticky regular expression.
let text;
const token = /\s+|\/\/[^\n]*|[A-Za-z_$][\w$]*|\d+(?:\.\d+)?|"(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'|[{}()[\];,.<>+\-*/%=!&|?:]/y;
function setup() {
  const line = 'const value = compute(alpha, 42.5, "a \\"quoted\\" string", beta) + gamma[3] * 7; // a comment\n';
  text = line.repeat(Math.ceil(2000000 / line.length));
}
function iteration() {
  token.lastIndex = 0;
  let count = 0;
  while (token.lastIndex < text.length) {
    if (!token.exec(text)) throw new Error('stuck at ' + token.lastIndex);
    count++;
  }
  return count;
}
