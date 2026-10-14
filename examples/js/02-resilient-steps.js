// 02 — Resilient steps and checkpoints.
//
// Run it:
//   c93 run examples/js/02-resilient-steps.js --on-orphan resume --max-attempts 3
//
// ctx.step runs a unit of work at most once per job: on a resumed attempt its stored result comes back
// and the function isn't called again. ctx.saveCheckpoint stores progress within a run. See
// docs/scripting/job-context.md.
export default async (input, ctx) => {
  if (ctx.isResume) {
    log.info('Resuming', { attempt: ctx.attempt, completed: ctx.completedSteps });
  }

  // Runs once; the durable result is returned on any later attempt.
  const computed = await ctx.step('compute', () => ({ val: 42 }));
  log.info('Computed', { val: computed.val, attempt: ctx.attempt });

  // A checkpoint for progress that isn't worth its own step.
  await ctx.saveCheckpoint({ step: 'done' });

  return { ...computed, checkpoint: { step: 'done' }, attempts: ctx.attempt };
};
