package cluster93.runner.protocol.frame;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Discriminator byte identifying the semantics of a protocol binary frame (D-127, worker-node-protocol.md §4).
 */
public enum FrameType {

    /** 0x01: Worker startup announcement with engine, version, and PID. Worker -> Node, Channel 0. */
    HELLO(0x01),

    /** 0x02: Node acceptance with assigned worker ID and protocol config. Node -> Worker, Channel 0. */
    WELCOME(0x02),

    /** 0x03: Permanently binds worker to one tenant with limits and env. Node -> Worker, Channel 0. */
    BIND(0x03),

    /** 0x04: Dispatches a script execution (bundle, input, context, timeouts). Node -> Worker, Channel: Run ID. */
    RUN(0x04),

    /** 0x05: Graceful stop request for a running service/job. Node -> Worker, Channel: Run ID. */
    STOP(0x05),

    /** 0x06: Forceful cancellation / interrupt of a running script. Node -> Worker, Channel: Run ID. */
    CANCEL(0x06),

    /** 0x07: Final execution outcome (ExecutionResult: return value or error). Worker -> Node, Channel: Run ID. */
    RESULT_RUN(0x07),

    /** 0x10: Host API RPC invocation ({runId, method, params}). Worker -> Node, Channel: Call ID. */
    CALL(0x10),

    /** 0x11: Successful answer to a host API RPC. Node -> Worker, Channel: Call ID. */
    RESULT(0x11),

    /** 0x12: Host API failure (HostException with code and message). Node -> Worker, Channel: Call ID. */
    ERROR(0x12),

    /** 0x13: Aborts an in-flight host API request. Worker -> Node, Channel: Call ID. */
    CANCEL_CALL(0x13),

    /** 0x20: Delivers an asynchronous bus, queue, or ingress event. Node -> Worker, Channel: Run ID. */
    EVENT(0x20),

    /** 0x21: Reply payload for request-response bus events. Worker -> Node, Channel: Event ID. */
    REPLY(0x21),

    /** 0x30: Establishes a new streaming channel (HTTP body, socket, file). Both directions, Channel: Stream ID. */
    OPEN(0x30),

    /** 0x31: Chunks of binary or text data on an active stream. Both directions, Channel: Stream ID. */
    DATA(0x31),

    /** 0x32: Orderly half-close of a streaming channel. Both directions, Channel: Stream ID. */
    END(0x32),

    /** 0x33: Abrupt stream termination (RST_STREAM). Both directions, Channel: Stream ID. */
    RESET(0x33),

    /** 0x34: Flow-control credit grants (per-stream or connection-wide). Both directions, Channel: Stream / 0. */
    CREDIT(0x34),

    /** 0x40: Buffered console stdout/stderr log lines. Worker -> Node, Channel: Run ID. */
    LOG(0x40),

    /** 0x41: Checkpoint or ctx.step state commit (durably acked). Worker -> Node, Channel: Run ID. */
    STATE(0x41),

    /** 0x42: Memory telemetry (RSS, dirty pages, GC pauses). Worker -> Node, Channel: 0 / Run ID. */
    STATS(0x42),

    /** 0x50: Heartbeat liveness probe. Both directions, Channel 0. */
    PING(0x50),

    /** 0x51: Heartbeat response. Both directions, Channel 0. */
    PONG(0x51),

    /** 0x5F: Orderly shutdown handshake before connection close. Both directions, Channel 0. */
    GOODBYE(0x5F);

    private final byte code;

    FrameType(int code) {
        this.code = (byte) code;
    }

    public byte code() {
        return code;
    }

    private static final Map<Byte, FrameType> BY_CODE = Arrays.stream(values())
            .collect(Collectors.toMap(FrameType::code, Function.identity()));

    /** Resolves the FrameType for a byte code, or throws IllegalArgumentException. */
    public static FrameType fromCode(byte code) {
        FrameType type = BY_CODE.get(code);
        if (type == null) {
            throw new IllegalArgumentException("Unknown frame type code: 0x" + Integer.toHexString(code & 0xFF));
        }
        return type;
    }
}
