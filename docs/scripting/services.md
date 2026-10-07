# Services and timers reference

**Status:** Implemented in phase 2b (2026-10-06). Design: [../design/services.md](../design/services.md). Job context basics: [job-context.md](job-context.md).

A **service** is a script the cluster keeps running until you stop it. It's like a job with no timeout: if it crashes or its node dies, it's started again somewhere healthy.

## Defining a service

```http
PUT /v1/services/order-poller
Authorization: Bearer atly_…

{
  "script": "export default async (input, ctx) => { ... }",
  "config": { "source": "https://example.com/orders", "everyMs": 30000 },
  "restart": "always"
}
```

- `restart: "always"` (default): a run that ends for any reason is restarted. Failures back off: 1 s, 2 s, 4 s, … up to 60 s. The counter resets once a run has stayed up for 60 s.
- `restart: "on-failure"`: a run that **returns normally** completes the service. Only failures restart it.
- Changing `script`, `config` or `restart` creates a new **revision**. The running instance is asked to stop and the new revision starts.
- `POST /v1/services/{name}/stop`, `/start`, `/restart`, and `DELETE /v1/services/{name}`.

## What a service script sees

```js
export default async (input, ctx) => {
  ctx.service.name;          // "order-poller"
  ctx.service.revision;      // 3
  ctx.service.run;           // 1 for the first run, then 2, 3, … across restarts
  ctx.service.config;        // the JSON from the definition
  ctx.service.previousRun;   // null, or { jobId, status, error, endedAt } for the run before this one
  ctx.checkpoint;            // the last checkpoint saved by the previous run (carried across restarts)
  ctx.stopRequested;         // a Promise that resolves when someone stops the service
  await ctx.sleep(1000);     // waits without blocking the isolate
};
```

`input` is the same object as `ctx.service.config`.

### Stopping gracefully
When a stop is requested, `ctx.stopRequested` resolves and the script gets **5 seconds** to finish and return. After that the isolate is terminated. A service that loops should watch for it:

```js
export default async (input, ctx) => {
  let stopping = false;
  ctx.stopRequested.then(() => { stopping = true; });

  let cursor = ctx.checkpoint?.cursor ?? null;
  while (!stopping) {
    const page = await source.fetchSince(input.source, cursor);   // a phase 3 host API
    for (const order of page.orders) {
      await handle(order);
      cursor = order.id;
    }
    await ctx.saveCheckpoint({ cursor });                          // survives crashes and restarts
    await Promise.race([ctx.sleep(input.everyMs), ctx.stopRequested]);
  }
  console.log(`stopping cleanly at ${cursor}`);
};
```

A run lasts until the default export's promise settles. Pending timers don't keep it alive on their own, so a timer-based service returns `ctx.stopRequested`:

```js
export default (input, ctx) => {
  setInterval(() => console.log("tick", new Date().toISOString()), input.everyMs);
  return ctx.stopRequested;   // resolves (and the run ends cleanly) when the service is stopped
};
```

## Timers (jobs and services)

`setTimeout`, `clearTimeout`, `setInterval`, `clearInterval` and `ctx.sleep(ms)` work in every script:
- Delays are clamped to at least 1 ms. At most 10 000 timers can be pending.
- A **job** still ends with "never settles" if its promise is pending with no timer and nothing else that could resolve it. A job that has looked at `ctx.stopRequested` is waiting for something real (a cancel can deliver it), so it waits instead, bounded by its timeout.
- A **service** in that state just waits (for example for `ctx.stopRequested`).

## Cancelling a job
`POST /v1/jobs/{id}/cancel` ends a job as `CANCELLED`. A running job gets the same graceful stop: `ctx.stopRequested` resolves, then 5 s later the isolate is terminated. Cancellation is durable. If the job's node dies before it stops, the job still ends `CANCELLED` and is never retried.
