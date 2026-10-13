// 04 — The event bus: request/reply and pub/sub.
//
// Run it:
//   c93 run examples/04-event-bus.js
//
// bus.handle answers requests; bus.subscribe listens to published messages. Both are tenant-scoped and
// at-most-once. See docs/scripting/host-apis.md.
export default async (input, ctx) => {
  // One handler answers requests to the topic.
  await bus.handle('echo', async (message) => ({ echo: message }));

  // A subscriber receives every published message; the returned function stops listening.
  const stop = await bus.subscribe('notifications', async (message, { topic }) => {
    log.info('Notification', { topic, message });
  });

  await bus.publish('notifications', { event: 'started' });

  const reply = await bus.request('echo', { hello: 'world' });
  log.info('Echo replied', reply);

  stop();
  return reply;
};
