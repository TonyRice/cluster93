// 01 — A basic job.
//
// Run it (from the repo root, after `c93 dev up`):
//   c93 run examples/js/01-hello-job.js --input '{"name":"Ada"}'
//
// A job script exports a default async function; it receives the job's input and its context.
export default async (input, ctx) => {
  const name = input?.name || 'world';
  log.info('Hello from a job', { jobId: ctx.jobId, name });
  return {
    greeting: `Hello, ${name}!`,
    timestamp: new Date().toISOString(),
  };
};
