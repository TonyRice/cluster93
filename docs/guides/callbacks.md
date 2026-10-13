# Completion callbacks

When a job ends, Cluster93 can POST its result to a URL you choose, so you don't have to poll or keep a stream open. This is the modern version of 2017's `callbackUrl`. Design: D-062.

## Asking for one

When you submit:
```http
POST /v1/jobs
{"script": "...", "input": {...},
 "callback": {"url": "https://example.com/hooks/c93", "data": {"order": 7}}}
```
From the CLI: `c93 run job.js --callback https://example.com/hooks/c93 --callback-data '{"order":7}'`.

From inside the script, including a service's run:
```js
export default async (input, ctx) => {
  await ctx.setCallback('https://example.com/hooks/c93', { order: input.order });
  // ...
};
```

`data` is any JSON up to 16 KB. It comes back unchanged, so you can tie the result to your own records.

## What you receive

```http
POST /hooks/c93
Content-Type: application/json
User-Agent: Cluster93-Callback/2
Cluster93-Signature: t=1791400000,v1=5f2c…
Cluster93-Delivery: k-job-9c72c86a-…
Cluster93-Attempt: 1

{"jobId": "9c72c86a-…", "status": "SUCCEEDED", "output": {...}, "error": null,
 "attempt": 1, "finishedAt": 1791400000123, "service": null, "data": {"order": 7}}
```

- **Which endings:** every one. `status` is `SUCCEEDED`, `FAILED`, `TIMED_OUT`, `CANCELLED` or `ORPHANED`, including jobs whose node died.
- **Answering:** any 2xx means delivered.
- **Retries:** anything else, or no answer within 10 s, is retried after 1 s, 4 s, 16 s, 64 s and 256 s, then every 10 min, for 8 attempts (about half an hour). `Cluster93-Attempt` says which attempt this is.
- **Failover:** deliveries go through a durable queue (D-061), so they continue if a node dies.
- **Duplicates:** a delivery can arrive more than once, for example if your answer was lost. Use `jobId` (or `Cluster93-Delivery`) to ignore repeats.
- **Seeing how it went:** the job shows the progress as `callback: {status: PENDING | DELIVERED | FAILED, attempts, deliveredAt, lastError}`.

The URL must be `http://` or `https://`, and it passes the same checks as `fetch`: private, loopback and cloud-metadata addresses are refused unless the operator allows them (D-026), and redirects aren't followed. A refused address fails at once instead of retrying.

## Checking the signature

Your tenant has one signing secret. Get it with `c93 callbacks secret`, or `GET /v1/callbacks/secret` with a `tokens:write` token. Replace it with `--rotate`, or `POST /v1/callbacks/secret/rotate`.

`v1` is the hex HMAC-SHA256, with the secret as key, of `t + "." + body`, where `body` is the **raw** request body. Compare in constant time, and reject old timestamps to stop replays:

```js
// Node.js
import crypto from 'node:crypto';

function verify(secret, header, rawBody, toleranceSeconds = 300) {
  const { t, v1 } = Object.fromEntries(header.split(',').map((part) => part.split('=')));
  const expected = crypto.createHmac('sha256', secret).update(`${t}.${rawBody}`).digest('hex');
  const fresh = Math.abs(Date.now() / 1000 - Number(t)) <= toleranceSeconds;
  return fresh && crypto.timingSafeEqual(Buffer.from(v1, 'hex'), Buffer.from(expected, 'hex'));
}
```

```python
# Python
import hashlib, hmac, time

def verify(secret: str, header: str, raw_body: bytes, tolerance: int = 300) -> bool:
    parts = dict(part.split("=", 1) for part in header.split(","))
    expected = hmac.new(secret.encode(), parts["t"].encode() + b"." + raw_body, hashlib.sha256).hexdigest()
    return abs(time.time() - int(parts["t"])) <= tolerance and hmac.compare_digest(parts["v1"], expected)
```
