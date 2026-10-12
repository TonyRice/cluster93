// 01 — A basic job.
//
// Run it (from the repo root, after `scripts/dev.sh start` and `scripts/dev.sh seed`):
//   automately run examples/01-hello-job.js --input '{"name":"Ada"}'
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
