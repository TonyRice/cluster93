package cluster93.core.service;

/** A service's state as shown by the API, derived from its definition and its current run. */
public enum ServiceStatus {
    /** Should be running; a run is queued or about to be started. */
    STARTING,
    RUNNING,
    /** The last run failed; waiting before the next start. */
    BACKOFF,
    /** A stop or a new revision was requested; the current run is shutting down. */
    STOPPING,
    STOPPED,
    /** {@code restart: on-failure} and the last run returned normally. */
    COMPLETED
}
