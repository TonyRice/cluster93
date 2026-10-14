// Node's `perf_hooks` (D-123): the isolate's `performance` (whole milliseconds, as everywhere in the isolate), and the
// rest with Node's shapes. An observer sees the marks and measures the script makes; the event loop's delay isn't
// measured, so its histograms stay empty.
export const performance = globalThis.performance;

const observers = new Set();
const SUPPORTED = Object.freeze(['mark', 'measure']);

export class PerformanceObserver {
  constructor(callback) {
    if (typeof callback !== 'function') {
      const err = new TypeError('The "callback" argument must be of type function');
      err.code = 'ERR_INVALID_ARG_TYPE';
      throw err;
    }
    this._callback = callback;
    this._types = new Set();
  }
  observe(options = {}) {
    const types = options.entryTypes ?? (options.type ? [options.type] : []);
    for (const type of types) if (SUPPORTED.includes(type)) this._types.add(type);
    observers.add(this);
  }
  disconnect() { observers.delete(this); }
  takeRecords() { return []; }
  static get supportedEntryTypes() { return SUPPORTED; }
}

// Marks and measures reach observers a turn later, as Node delivers them.
globalThis[Symbol.for('cluster93.performanceObserved')]((entry) => {
  for (const observer of observers) {
    if (observer._types.has(entry.entryType)) {
      const list = { getEntries: () => [entry], getEntriesByName: (n) => (entry.name === n ? [entry] : []), getEntriesByType: (t) => (entry.entryType === t ? [entry] : []) };
      setImmediate(() => observer._callback(list, observer));
    }
  }
});

class Histogram {
  constructor() { this.min = 9223372036854776000; this.max = 0; this.mean = NaN; this.stddev = NaN; this.count = 0; this.exceeds = 0; this.percentiles = new Map(); }
  percentile() { return 0; }
  reset() {}
  enable() { return true; }
  disable() { return true; }
  record() {}
  recordDelta() {}
}

export function monitorEventLoopDelay() { return new Histogram(); }
export function createHistogram() { return new Histogram(); }
export const constants = Object.freeze({
  NODE_PERFORMANCE_GC_MAJOR: 4, NODE_PERFORMANCE_GC_MINOR: 1, NODE_PERFORMANCE_GC_INCREMENTAL: 8, NODE_PERFORMANCE_GC_WEAKCB: 16,
  NODE_PERFORMANCE_GC_FLAGS_NO: 0, NODE_PERFORMANCE_GC_FLAGS_CONSTRUCT_RETAINED: 2, NODE_PERFORMANCE_GC_FLAGS_FORCED: 4,
  NODE_PERFORMANCE_GC_FLAGS_SYNCHRONOUS_PHANTOM_PROCESSING: 8, NODE_PERFORMANCE_GC_FLAGS_ALL_AVAILABLE_GARBAGE: 16,
  NODE_PERFORMANCE_GC_FLAGS_ALL_EXTERNAL_MEMORY: 32, NODE_PERFORMANCE_GC_FLAGS_SCHEDULE_IDLE: 64,
});

export default { performance, PerformanceObserver, monitorEventLoopDelay, createHistogram, constants };
