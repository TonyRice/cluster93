// The last file: runs the job's default export and hands the event loop its entry points (ScriptEntryPoints).
// In a service, Node servers that listen keep the run going after the default export returns, until they all close
// or the service is stopped; a service module that only listens needs no default export, as a plain Express app.js
// (D-123).
const invoke = async (fn, inputJson, contextJson) => {
  try {
    const c = JSON.parse(contextJson);
    let output = null;
    if (typeof fn === 'function') {
      const value = await fn(JSON.parse(inputJson), makeContext(c));
      output = value === undefined ? null : value;
    } else if (!c.service || !(await serversStarted())) {
      return JSON.stringify({ ok: false, error: c.service
        ? 'The script must export a default function, or start a server with listen'
        : 'The script must export a default function' });
    }
    if (c.service) await whenServersClose();
    return JSON.stringify({ ok: true, output });
  } catch (e) {
    return JSON.stringify({ ok: false, error: errorText(e) });
  }
};
// Whether the module started a server: a listen() made while it loaded may still be on its way to the host.
const serversStarted = () => new Promise((resolve) => setImmediate(() => resolve(openServers > 0)));
return { invoke, fire, startImmediates, runImmediate, requestStop, deliver, request, chunk, streamError, rejected, handled, checkRejections };
