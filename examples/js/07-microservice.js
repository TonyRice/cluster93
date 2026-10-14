// 07 — A microservice.
//
// c93: service — this example serves HTTP, so the runner deploys it instead of running it as a job.
//
// Deploy it as a service (a plain job may not serve HTTP):
//   c93 services deploy 07-microservice examples/js/07-microservice.js
//
// It is then reachable at http://07-microservice--dev.localhost:9394/ on the ingress port. The handler
// returns the response: a string (200, text/plain), or {status, headers, body} where an object body
// becomes JSON. See docs/scripting/services.md.
export default async (input, ctx) => {
  await ctx.serve(async (req) => ({
    status: 200,
    body: { message: 'hello world', method: req.method, path: req.path },
  }));

  // Keep serving until the service is stopped.
  return ctx.stopRequested;
};
