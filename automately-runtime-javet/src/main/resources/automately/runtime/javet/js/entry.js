// The last file: runs the job's default export and hands the event loop its entry points (ScriptEntryPoints).
const invoke = async (fn, inputJson, contextJson) => {
  try {
    const output = await fn(JSON.parse(inputJson), makeContext(JSON.parse(contextJson)));
    return JSON.stringify({ ok: true, output: output === undefined ? null : output });
  } catch (e) {
    return JSON.stringify({ ok: false, error: errorText(e) });
  }
};
return { invoke, fire, requestStop, deliver, request, chunk, streamError };
