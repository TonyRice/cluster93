package automately.core.host;

import automately.core.auth.RateLimiter;
import automately.core.job.JobStore;
import automately.core.scheduler.JobScheduler;

import java.time.Duration;

/** What the {@code jobs} API needs from the node (D-063): the jobs, the scheduler, the API's limits and its rate limiter. */
public record JobsWiring(JobStore jobs, JobScheduler scheduler, Duration defaultTimeout, Duration maxTimeout, RateLimiter limiter) {
}
