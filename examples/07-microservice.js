// 07 — A microservice.
//
// Deploy it as a service (a plain job may not serve HTTP):
//   automately services deploy echo examples/07-microservice.js
//
// It is then reachable at http://echo--dev.localhost:8000/ on the ingress port. The handler receives a
// request and returns the response: a string, bytes, an object (sent as JSON), or {status, headers, body}.
// See docs/scripting/services.md.
export default async (input, ctx) => {
  await ctx.serve(async (req) => ({
    status: 'ok',
    method: req.method,
    path: req.path,
  }));

  // Keep serving until the service is stopped.
  return ctx.stopRequested;
};
