// 05 — Durable queues.
//
// Run it:
//   automately run examples/05-durable-queues.js
//
// A queue keeps messages in the cluster until a consumer has handled them (at least once), even if the
// consumer's node dies. Consume them from a service with queue.consume. See docs/scripting/host-apis.md.
export default async (input, ctx) => {
  const jobs = bus.queue('jobs');

  const id = await jobs.push({ id: 1 });

  const stats = await jobs.stats();
  log.info('Queued a job', { id, stats });

  return { id, stats };
};
