package cluster93.core.host;

import cluster93.core.auth.RateLimiter;
import cluster93.core.job.JobStore;
import cluster93.core.scheduler.JobScheduler;

import java.time.Duration;

/** What the {@code jobs} API needs from the node (D-063): the jobs, the scheduler, the API's limits and its rate limiter. */
public record JobsWiring(JobStore jobs, JobScheduler scheduler, Duration defaultTimeout, Duration maxTimeout, RateLimiter limiter) {
}
