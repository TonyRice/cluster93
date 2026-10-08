// A stand-in for Node's `timers/promises` (D-103), on the isolate's own timers.

function abortError(signal) {
  return Object.assign(new Error('The operation was aborted'), { name: 'AbortError', code: 'ABORT_ERR', cause: signal?.reason });
}

function delayed(delay, value, options, schedule) {
  const signal = options?.signal;
  if (signal?.aborted) return Promise.reject(abortError(signal));
  return new Promise((resolve, reject) => {
    const id = schedule(() => {
      signal?.removeEventListener?.('abort', onAbort);
      resolve(value);
    }, delay);
    function onAbort() {
      clearTimeout(id);
      reject(abortError(signal));
    }
    signal?.addEventListener?.('abort', onAbort, { once: true });
  });
}

export function setTimeout(delay, value, options) {
  return delayed(delay, value, options, (fn, ms) => globalThis.setTimeout(fn, ms));
}

export function setImmediate(value, options) {
  return delayed(0, value, options, (fn) => globalThis.setTimeout(fn, 0));
}

export async function* setInterval(delay, value, options) {
  const signal = options?.signal;
  if (signal?.aborted) throw abortError(signal);
  let ticks = 0;
  let wake = null;
  const id = globalThis.setInterval(() => {
    ticks++;
    if (wake) {
      const w = wake;
      wake = null;
      w();
    }
  }, delay);
  const onAbort = () => {
    if (wake) {
      const w = wake;
      wake = null;
      w();
    }
  };
  signal?.addEventListener?.('abort', onAbort, { once: true });
  try {
    for (;;) {
      if (ticks === 0) await new Promise((resolve) => { wake = resolve; });
      if (signal?.aborted) throw abortError(signal);
      for (; ticks > 0; ticks--) yield value;
    }
  } finally {
    clearInterval(id);
    signal?.removeEventListener?.('abort', onAbort);
  }
}

export const scheduler = Object.freeze({
  wait: (delay, options) => setTimeout(delay, undefined, options),
  yield: () => setImmediate(),
});

export default { setTimeout, setImmediate, setInterval, scheduler };
