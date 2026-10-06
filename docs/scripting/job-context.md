# Job context reference: attempts, steps and checkpoints

**Status:** Implemented in phase 2 (2026-10-06). Design and reasoning: [design/orphaned-jobs.md](../design/orphaned-jobs.md).

Every job script receives a second argument, `ctx`. It tells the script whether this is a fresh start, a retry or a resume, and lets it record progress so a resumed job **doesn't repeat work that already finished**.

```js
export default async (input, ctx) => { ... }
```

---

## 1. Choosing a policy when submitting

```http
POST /v1/jobs
{
  "script": "...",
  "input": { ... },
  "onOrphan": "resume",     // "fail" (default) | "retry" | "resume"
  "maxAttempts": 3          // 1–10. Defaults to 3 for retry/resume and must be 1 for fail
}
```

`onOrphan` decides what happens if the node running the job **disappears** (crash, kill, network loss). It doesn't affect jobs that end normally. A script that throws, times out or exceeds memory is `FAILED`/`TIMED_OUT` and is **never** retried automatically.

| Policy | When the node disappears | Job ends as |
|---|---|---|
| `fail` | Nothing is re-run | `ORPHANED` |
| `retry` | A new attempt runs the script **from the start**, with no checkpoint and no recorded steps | Result of the last attempt, or `ORPHANED` after `maxAttempts` |
| `resume` | A new attempt runs the script with the **last checkpoint and completed steps** | Result of the last attempt, or `ORPHANED` after `maxAttempts` |

---

## 2. The `ctx` object

| Member | Type | Meaning |
|---|---|---|
| `ctx.jobId` | `string` | The job's id |
| `ctx.attempt` | `number` | 1 for the first run, then 2, 3 and so on |
| `ctx.policy` | `"fail" \| "retry" \| "resume"` | As submitted |
| `ctx.isRetry` | `boolean` | `attempt > 1` and the policy is `retry` |
| `ctx.isResume` | `boolean` | `attempt > 1` and the policy is `resume` |
| `ctx.previous` | `object \| null` | The attempt before this one: `{ attempt, node, outcome, startedAt, endedAt }`. `outcome` is `"ORPHANED"` |
| `ctx.checkpoint` | JSON value `\| null` | The last value passed to `saveCheckpoint`. Only set when resuming |
| `ctx.completedSteps` | `string[]` | Names of steps that already finished. Empty unless resuming |
| `await ctx.saveCheckpoint(value)` | `Promise<void>` | Stores `value` durably before resolving |
| `await ctx.step(name, fn)` | `Promise<result>` | Runs `fn` at most once per job (see below) |
| `ctx.stepKey(name)` | `string` | `"<jobId>:<name>"`, a stable idempotency key |

### `ctx.step(name, fn)`
- If the step **already completed** in an earlier attempt (resume only), it returns the stored result. `fn` is **not called**.
- Otherwise it calls `fn({ key, attempt })` and awaits it. If `fn` succeeds, the result is stored **durably**, and then `step` resolves with it.
- If `fn` throws, nothing is stored and the error propagates like any other.
- The result must be JSON-serialisable and at most **64 KB**. `undefined` is stored as `null`.
- Names must be unique within a run. Calling `step` twice with the same name in one attempt throws.
- `key` equals `ctx.stepKey(name)` and is the same in every attempt. Pass it to any outside system that accepts an idempotency key.

### `ctx.saveCheckpoint(value)`
- Replaces the previous checkpoint. The value must be JSON and at most **64 KB**.
- Use it for progress *within* a long loop, where naming every iteration as a step would be wasteful (for example "processed up to row 14 000").

### Limits and errors
- Total stored state per job (all step results plus the checkpoint) is at most **1 MB**. Going over it throws `StateLimitError`.
- If this attempt has been **superseded** (the cluster decided the node was dead and started a newer attempt), `step` and `saveCheckpoint` throw `SupersededError` and the isolate is terminated shortly after (at the node's next heartbeat). Let it propagate; this attempt's work no longer counts.
- `SupersededError` and `StateLimitError` are read-only globals, so `e instanceof SupersededError` and `e.name === "SupersededError"` both work.
- Steps and checkpoints work under every policy. With `fail` they're still recorded and visible through `GET /v1/jobs/{id}/steps`, which helps with debugging, but no later attempt uses them.

---

## 3. A complete resumable script

This sends a batch of invoices. Each customer's invoice is its own step, and a crash at any point resumes from the first customer not yet handled:

```js
// Submit with: { "onOrphan": "resume", "maxAttempts": 3, "input": { "batchId": "2026-10", "customers": [...] } }
export default async (input, ctx) => {
  if (ctx.isResume) {
    console.log(`Resuming batch ${input.batchId}: attempt ${ctx.attempt}, ` +
                `${ctx.completedSteps.length} steps already done ` +
                `(previous attempt on ${ctx.previous.node} ended ${ctx.previous.outcome})`);
  }

  // A step that ran before isn't repeated: its stored result is returned.
  const batch = await ctx.step("open-batch", ({ key }) =>
    billing.openBatch({ batchId: input.batchId, idempotencyKey: key }));

  let sent = 0;
  for (const customer of input.customers) {
    // One step per customer. A resumed attempt skips customers that already have one.
    const invoice = await ctx.step(`invoice:${customer.id}`, ({ key }) =>
      billing.createInvoice({ batch: batch.id, customer: customer.id, idempotencyKey: key }));

    await ctx.step(`email:${customer.id}`, ({ key }) =>
      mail.send({ to: customer.email, template: "invoice", invoiceId: invoice.id, idempotencyKey: key }));

    sent++;
  }

  await ctx.step("close-batch", () => billing.closeBatch(batch.id));
  return { batch: batch.id, customers: sent, attempts: ctx.attempt };
};
```

> `billing` and `mail` stand for host APIs that arrive in phase 3 (`http`, `kv` and others). The pattern is the same whatever API is called.

**What happens if the node dies after `email:c-17`:**

| | Attempt 1 (node1) | Attempt 2 (node3) |
|---|---|---|
| `open-batch` | runs, result stored | returns the stored result, **not re-run** |
| `invoice:c-1` … `email:c-17` | run, results stored | returned from storage, **not re-run** |
| `invoice:c-18` | node1 dies mid-call ⚠️ | runs again with the **same** idempotency key |
| `email:c-18` onwards | — | runs normally |

The ⚠️ row is the one case that can't be fully avoided: the call to the outside system may have succeeded just before the crash, before the step's result was saved. Passing `key` lets that system recognise the repeat and not act on it twice.

---

## 4. Checkpoint pattern for long loops

```js
// { "onOrphan": "resume" }
export default async (input, ctx) => {
  let { page, total } = ctx.checkpoint ?? { page: 1, total: 0 };

  while (true) {
    const rows = await source.fetchPage(input.dataset, page);   // reading is safe to repeat
    if (rows.length === 0) break;

    total += await sink.upsert(rows);                            // an idempotent write: upsert by row id
    page += 1;
    await ctx.saveCheckpoint({ page, total });                   // durable before the loop continues
  }
  return { pages: page - 1, total };
};
```

If the job is resumed, it starts at the page after the last saved checkpoint. At most one page is processed twice, which is why the write is an upsert.

---

## 5. Retry pattern: safe to start over

```js
// { "onOrphan": "retry", "maxAttempts": 5 }
export default async (input, ctx) => {
  if (ctx.isRetry) console.warn(`Attempt ${ctx.attempt}; previous one was ${ctx.previous.outcome}`);
  const report = await analytics.compute(input.range);   // pure computation, so repeating it is harmless
  return report;
};
```

---

## 6. Default (`fail`): finding out instead of repeating

```js
// Submitted without onOrphan, so the policy is "fail"
export default async (input, ctx) => {
  await ctx.step("charge", ({ key }) => payments.charge({ amount: input.amount, idempotencyKey: key }));
  await ctx.step("receipt", () => mail.send({ to: input.email, template: "receipt" }));
};
```

If the node dies, the job ends as `ORPHANED` and nothing re-runs. `GET /v1/jobs/{id}/steps` shows exactly how far it got (for example `charge` done and `receipt` missing), so a person or a follow-up job can decide what to do.

---

## 7. Browser flows (future, see [NOTES.md](../../NOTES.md))

A live browser page can't move to another machine, so a resumed browser job starts a **new** browser session and skips the completed steps. Write flows so that each step can rebuild what it needs:

```js
await ctx.step("login", () => browser.login(site, creds));                      // re-run if not done
const orders = await ctx.step("collect-orders", () => browser.scrape(site, "/orders"));
for (const o of orders) {
  await ctx.step(`download:${o.id}`, () => browser.download(site, o.invoiceUrl));
}
```

---

## 8. Seeing it from the API

| Endpoint / event | Shows |
|---|---|
| `GET /v1/jobs/{id}` | `status` (including `ORPHANED`), `attempt`, `attempts[]`, `onOrphan`, `maxAttempts` |
| `GET /v1/jobs/{id}/steps` | `checkpoint`, plus `steps[]` with `name`, `attempt` and `completedAt` for each, and `result` |
| SSE `attempt` event | `{"type":"attempt","attempt":2,"previousOutcome":"ORPHANED","node":"node3"}` when a new attempt starts |
| SSE `end` event | Sent only when the **job** is finished, not when a single attempt ends |
