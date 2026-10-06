package automately.core.scheduler;

/** Event bus addresses used between the scheduler, nodes and API. */
public final class Addresses {

    private Addresses() {
    }

    /** Point-to-point: ask one node instance to run an attempt. Body: {@code {"jobId", "attempt"}}. */
    public static String nodeRun(String instanceId) {
        return "automately.node." + instanceId + ".run";
    }

    /** Point-to-point: ask one node instance to stop an attempt. Body: {@code {"jobId", "attempt", "reason"}}. */
    public static String nodeControl(String instanceId) {
        return "automately.node." + instanceId + ".control";
    }

    /** Publish: {@code log}, {@code attempt} and the final {@code end} event of one job. */
    public static String jobEvents(String jobId) {
        return "automately.job." + jobId + ".events";
    }
}
