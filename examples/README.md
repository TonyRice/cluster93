# Examples

Runnable Cluster93 scripts, organised by language. Start the dev stack first:

```bash
c93 dev up
```

## JavaScript (`js/`)

**Jobs** — run with `c93 run`:

| | |
|---|---|
| [`01-hello-job.js`](js/01-hello-job.js) | A basic job: input in, a result out. |
| [`02-resilient-steps.js`](js/02-resilient-steps.js) | `ctx.step` checkpoints and orphan resume. |
| [`03-kv-storage.js`](js/03-kv-storage.js) | The tenant's replicated JSON key-value store. |
| [`04-event-bus.js`](js/04-event-bus.js) | Request/reply and pub/sub on the event bus. |
| [`05-durable-queues.js`](js/05-durable-queues.js) | Durable queues with at-least-once delivery. |
| [`06-vfs-files.js`](js/06-vfs-files.js) | The virtual file system. |

**A service** — long-running, serves HTTP, deployed with `c93 services deploy`:

| | |
|---|---|
| [`07-microservice.js`](js/07-microservice.js) | An HTTP handler served on the ingress. |

Run every example (the jobs *and* the service) with:

```bash
c93 dev test-examples
```

## Python (`python/`)

Planned (Phase 9): the directory appears when the Python runtime lands.

## Adding an example

Drop a `.js` file in `js/` — the runner iterates the directory, so no script edit is needed. A file
whose header carries a `// c93: service` line is deployed as a service (and removed again) instead of
being run as a job.
