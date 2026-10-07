// ctx: the job's context (docs/scripting/job-context.md). Steps and checkpoints are stored synchronously by HostState.
class SupersededError extends Error { constructor(message) { super(message); this.name = 'SupersededError'; } }
class StateLimitError extends Error { constructor(message) { super(message); this.name = 'StateLimitError'; } }
for (const [name, value] of [['SupersededError', SupersededError], ['StateLimitError', StateLimitError]]) {
  Object.freeze(value.prototype);
  define(name, Object.freeze(value));
}
const store = (method, ...args) => {
  const reply = JSON.parse(host[method](...args));
  if (reply.ok) return;
  if (reply.kind === 'SUPERSEDED') throw new SupersededError(reply.message);
  if (reply.kind === 'LIMIT') throw new StateLimitError(reply.message);
  throw new Error(reply.message);
};
const toJson = (value, what) => {
  const json = JSON.stringify(value === undefined ? null : value);
  if (json === undefined) throw new TypeError(`${what} must be JSON-serialisable`);
  return json;
};

let resolveStop;
const stopRequested = new Promise((resolve) => { resolveStop = resolve; });
// Called by the event loop when the job is asked to stop.
const requestStop = (reason) => resolveStop({ reason });

const deepFreeze = (value) => {
  if (value && typeof value === 'object') { Object.values(value).forEach(deepFreeze); Object.freeze(value); }
  return value;
};

const makeContext = (c) => {
  const done = new Map(Object.entries(c.steps));
  const seen = new Set();
  const resuming = c.attempt > 1 && c.policy === 'resume';
  const stepKey = (name) => `${c.jobId}:${name}`;
  return Object.freeze({
    jobId: c.jobId,
    attempt: c.attempt,
    policy: c.policy,
    isRetry: c.attempt > 1 && c.policy === 'retry',
    isResume: resuming,
    previous: c.previous ? Object.freeze(c.previous) : null,
    checkpoint: c.checkpoint ?? null,
    completedSteps: Object.freeze([...done.keys()]),
    service: c.service ? deepFreeze(c.service) : null,
    get stopRequested() { host.watchStop(); return stopRequested; },
    serve: (handler) => {
      if (!c.service) throw new Error('Only services can serve HTTP; define this script as a service');
      if (typeof handler !== 'function') throw new TypeError('ctx.serve needs a handler function');
      return serve(handler);
    },
    sleep: (ms) => new Promise((resolve) => schedule(resolve, ms, [], false)),
    // Where this job's result is POSTed when it ends, signed (D-062); `data` comes back with it.
    setCallback: (url, data) => host.jobs.setCallback(url, data),
    stepKey,
    async saveCheckpoint(value) {
      store('saveCheckpoint', toJson(value, 'A checkpoint'));
    },
    async step(name, fn) {
      if (typeof name !== 'string' || name.length === 0) throw new TypeError('A step name must be a non-empty string');
      if (typeof fn !== 'function') throw new TypeError(`Step "${name}" needs a function`);
      if (seen.has(name)) throw new Error(`Step "${name}" was already used in this run; step names must be unique`);
      seen.add(name);
      if (done.has(name)) return JSON.parse(JSON.stringify(done.get(name)));
      const result = await fn(Object.freeze({ key: stepKey(name), attempt: c.attempt }));
      const json = toJson(result, `The result of step "${name}"`);
      store('completeStep', name, json);
      return JSON.parse(json);
    },
  });
};
