package cluster93.runner.protocol.message;

import cluster93.runner.protocol.frame.Frame;
import cluster93.runner.protocol.frame.FrameType;
import io.vertx.core.json.JsonObject;

/**
 * Worker announcement upon process startup (worker-node-protocol.md §4).
 *
 * <p>Three fields beyond its identity exist because a worker may <b>connect</b> rather than be spawned (D-166): the
 * {@code secret} it presents, and the {@code heapMb}/{@code maxJobs} it declares so the node can reserve memory for
 * it from the moment it connects (D-169). All three are optional — a worker the node spawned over a pipe presents no
 * secret and needs to declare nothing, because the node sized it itself. {@code egressPolicy} says this worker can
 * apply the rules the node sends it (D-173); a worker that doesn't say so is never sent one, so a node upgrade cannot
 * kill the workers of the version before it.
 */
public record HelloMessage(String engine, String version, long pid, String secret, int heapMb, int maxJobs,
                           boolean egressPolicy) {

    /** A worker that presents no secret and declares nothing: one the node spawned and sized over a pipe (D-127). */
    public HelloMessage(String engine, String version, long pid) {
        this(engine, version, pid, null, 0, 0, false);
    }

    /** A worker that predates the policy frame: the node keeps deciding egress for it (D-173). */
    public HelloMessage(String engine, String version, long pid, String secret, int heapMb, int maxJobs) {
        this(engine, version, pid, secret, heapMb, maxJobs, false);
    }

    public Frame toFrame() {
        JsonObject json = new JsonObject()
                .put("engine", engine)
                .put("version", version)
                .put("pid", pid);
        // A pipe binds a channel to the child the node itself made; a socket binds it to whoever connected, so a
        // connected worker proves itself with the secret both sides read (D-147, D-168).
        if (secret != null && !secret.isEmpty()) {
            json.put("secret", secret);
        }
        if (heapMb > 0) {
            json.put("heapMb", heapMb);
        }
        if (maxJobs > 0) {
            json.put("maxJobs", maxJobs);
        }
        // Only a worker that understands the policy frame is sent one (D-173): a node must not send a frame a worker
        // predates, or the worker dies on its first bind. Absent means no.
        if (egressPolicy) {
            json.put("egressPolicy", true);
        }
        return Frame.control(FrameType.HELLO, json);
    }

    public static HelloMessage fromFrame(Frame frame) {
        if (frame.type() != FrameType.HELLO) {
            throw new IllegalArgumentException("Expected HELLO frame, got " + frame.type());
        }
        JsonObject json = frame.json();
        return new HelloMessage(
                json.getString("engine", "unknown"),
                json.getString("version", "unknown"),
                json.getLong("pid", 0L),
                json.getString("secret"),
                json.getInteger("heapMb", 0),
                json.getInteger("maxJobs", 0),
                json.getBoolean("egressPolicy", false)
        );
    }
}
