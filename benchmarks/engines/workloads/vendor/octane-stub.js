// Just enough of Octane's base.js for richards.js and deltablue.js to load (BSD, the V8 project authors).
function BenchmarkSuite(name, reference, benchmarks) { this.name = name; this.benchmarks = benchmarks; }
function Benchmark(name, doWarmup, doDeterministic, deterministicIterations, run, setup, tearDown, rmsResult, minIterations) {
  this.name = name; this.run = typeof doWarmup === 'function' ? doWarmup : run;
}
