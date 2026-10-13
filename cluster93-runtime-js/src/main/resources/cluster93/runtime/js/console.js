// console and log: lines go to the job's log (and its live event stream).
// The rest of console's methods too (D-105): libraries call them, and a missing one threw. trace adds the caller's
// stack; group indents what follows; time and count keep their labels for this job only.
let indent = '';
// A line is clipped here, before it's copied to the host, and again there (D-107): the job keeps about 16 KiB per line.
const MAX_LINE_CHARS = 16384;
const clip = (text) => {
  if (text.length <= MAX_LINE_CHARS) return text;
  const end = (text.charCodeAt(MAX_LINE_CHARS - 1) & 0xfc00) === 0xd800 ? MAX_LINE_CHARS - 1 : MAX_LINE_CHARS;
  return text.slice(0, end) + ' … [' + (text.length - end) + ' more characters]';
};
const consoleTimers = new Map();
const consoleCounts = new Map();
const indented = (level) => (...args) => host.log(level, clip(indent + args.map(show).join(' ')));
globalThis.console = Object.freeze({
  log: indented('info'), info: indented('info'), warn: indented('warn'), error: indented('error'), debug: indented('debug'),
  dir: (value) => indented('info')(value),
  table: (value) => indented('info')(value),
  trace: (...args) => indented('debug')(...args, '\n' + String(new Error().stack).split('\n').slice(2).join('\n')),
  assert: (condition, ...args) => { if (!condition) indented('error')('Assertion failed' + (args.length ? ':' : ''), ...args); },
  group: (...args) => { if (args.length) indented('info')(...args); indent += '  '; },
  groupCollapsed: (...args) => { if (args.length) indented('info')(...args); indent += '  '; },
  groupEnd: () => { indent = indent.slice(2); },
  time: (label = 'default') => { consoleTimers.set(String(label), Date.now()); },
  timeLog: (label = 'default', ...args) => {
    const started = consoleTimers.get(String(label));
    if (started === undefined) indented('warn')("No such label '" + label + "' for console.timeLog()");
    else indented('info')(label + ': ' + (Date.now() - started) + 'ms', ...args);
  },
  timeEnd: (label = 'default') => {
    const started = consoleTimers.get(String(label));
    if (started === undefined) { indented('warn')("No such label '" + label + "' for console.timeEnd()"); return; }
    consoleTimers.delete(String(label));
    indented('info')(label + ': ' + (Date.now() - started) + 'ms');
  },
  count: (label = 'default') => {
    const n = (consoleCounts.get(String(label)) ?? 0) + 1;
    consoleCounts.set(String(label), n);
    indented('info')(label + ': ' + n);
  },
  countReset: (label = 'default') => { consoleCounts.delete(String(label)); },
});

const fieldsJson = (fields) => {
  if (fields === undefined || fields === null) return 'null';
  let json;
  try { json = JSON.stringify(fields); } catch { json = JSON.stringify({ fields: String(fields) }); }
  return json.length > MAX_LINE_CHARS ? JSON.stringify({ fieldsOmitted: json.length + ' characters' }) : json;
};
const structured = (level) => (message, fields) => host.logFields(level, clip(show(message)), fieldsJson(fields));
define('log', Object.freeze({ debug: structured('debug'), info: structured('info'), warn: structured('warn'), error: structured('error') }));
