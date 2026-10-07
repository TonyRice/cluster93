// console and log: lines go to the job's log (and its live event stream).
const write = (level) => (...args) => host.log(level, args.map(show).join(' '));
globalThis.console = Object.freeze({
  log: write('info'), info: write('info'), warn: write('warn'), error: write('error'),
});

const fieldsJson = (fields) => {
  if (fields === undefined || fields === null) return 'null';
  try { return JSON.stringify(fields); } catch { return JSON.stringify({ fields: String(fields) }); }
};
const structured = (level) => (message, fields) => host.logFields(level, show(message), fieldsJson(fields));
define('log', Object.freeze({ debug: structured('debug'), info: structured('info'), warn: structured('warn'), error: structured('error') }));
