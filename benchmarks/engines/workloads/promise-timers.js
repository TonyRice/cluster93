// The event loop: promise chains, setImmediate and zero-delay timers.
async function iteration() {
  let value = 0;
  for (let k = 0; k < 2000; k++) value = await Promise.resolve(value + 1);
  await Promise.all(Array.from({ length: 500 }, (_, k) => new Promise((resolve) => setImmediate(() => resolve(k)))));
  for (let k = 0; k < 50; k++) await new Promise((resolve) => setTimeout(resolve, 0));
  return value;
}
