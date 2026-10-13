// The benchmark harness, appended to a workload (D-126 §8). The workload defines iteration(i), and optionally setup().
// One long-lived run: the first iteration is timed on its own (cold), then the workload warms up for at least
// warmupMs and warmupMin iterations, then each of `measured` iterations is timed. Timing is the worker's: it stamps
// the marks below with System.nanoTime() as they're logged, so both engines are measured the same way.
export default async (input) => {
  if (typeof setup === 'function') await setup();
  console.log('\u0001bench:first');
  await iteration(0);
  console.log('\u0001bench:first-end');
  const started = Date.now();
  let warm = 1;
  while (warm < input.warmupMin || Date.now() - started < input.warmupMs) {
    await iteration(warm);
    warm++;
  }
  const warmupMs = Date.now() - started;
  for (let i = 0; i < input.measured; i++) {
    console.log('\u0001bench:start');
    await iteration(warm + i);
    console.log('\u0001bench:end');
  }
  return { warmupIterations: warm, warmupMs };
};
