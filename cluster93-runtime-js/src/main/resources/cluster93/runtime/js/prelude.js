// The prelude (D-060): the files in this folder, in JavetScriptRuntime.PRELUDE_FILES order, inside one closure that
// runs before the job's module. The work is done in Java (the *Binding classes over the host APIs); these files only
// make it idiomatic JavaScript and freeze what the script sees.
//
// This first file captures the host object and removes it from the global scope, so the script never sees it.
const host = globalThis.__c93;
delete globalThis.__c93;

const define = (name, value) => Object.defineProperty(globalThis, name, { value, writable: false, configurable: false });
const show = (value) => {
  if (typeof value === 'string') return value;
  try { return JSON.stringify(value); } catch { return String(value); }
};
const errorText = (e) => String(e && e.stack ? e.stack : e);
