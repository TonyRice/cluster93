// jobs: JobsBinding (D-063). Start a job from a script and follow it, as 2017's Automately.startJob did, without blocking:
// the handle's result() is a promise. A function is sent as its source, so it can't use variables from around it.
const jobHandle = (id) => Object.freeze({
  id,
  result: () => host.jobs.result(id),
  events: (handler) => {
    if (typeof handler !== 'function') throw new TypeError('A handler must be a function');
    return listen((listenerId) => host.jobs.events(id, listenerId), handler);
  },
  stop: (reason) => host.jobs.stop(id, reason),
});
define('jobs', Object.freeze({
  run: async (script, input, options) => {
    const source = typeof script === 'function' ? `export default ${script.toString()}` : script;
    const job = await host.jobs.run(source, input, options);
    return jobHandle(job.id);
  },
  get: (id) => host.jobs.get(id),
  stop: (id, reason) => host.jobs.stop(id, reason),
  handle: (id) => jobHandle(String(id)),
}));
