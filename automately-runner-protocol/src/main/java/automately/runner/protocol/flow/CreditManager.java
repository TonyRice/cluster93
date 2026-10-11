package automately.runner.protocol.flow;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages HTTP/2-style credit windows for streams and connections (D-127, worker-node-protocol.md §5).
 */
public final class CreditManager {

    /** Default initial credit window for each stream: 256 KiB (262,144 bytes). */
    public static final int DEFAULT_STREAM_INITIAL_WINDOW = 256 * 1024;

    /** Global maximum buffer cap for the connection: 4 MiB (4,194,304 bytes). */
    public static final int DEFAULT_CONNECTION_MAX_WINDOW = 4 * 1024 * 1024;

    private final int streamInitialWindow;
    private final int connectionMaxWindow;
    private final AtomicInteger connectionCredits;
    private final ConcurrentHashMap<Integer, AtomicInteger> streamCredits = new ConcurrentHashMap<>();

    public CreditManager() {
        this(DEFAULT_STREAM_INITIAL_WINDOW, DEFAULT_CONNECTION_MAX_WINDOW);
    }

    public CreditManager(int streamInitialWindow, int connectionMaxWindow) {
        this.streamInitialWindow = streamInitialWindow;
        this.connectionMaxWindow = connectionMaxWindow;
        this.connectionCredits = new AtomicInteger(connectionMaxWindow);
    }

    /**
     * Initializes credit window for a newly opened stream.
     */
    public void openStream(int streamId) {
        streamCredits.put(streamId, new AtomicInteger(streamInitialWindow));
    }

    /**
     * Closes and removes a stream's credit window.
     */
    public void closeStream(int streamId) {
        streamCredits.remove(streamId);
    }

    /**
     * Checks if a stream is registered.
     */
    public boolean hasStream(int streamId) {
        return streamCredits.containsKey(streamId);
    }

    /**
     * Grants additional credits to a specific stream.
     */
    public int addStreamCredits(int streamId, int bytesToAdd) {
        if (bytesToAdd <= 0) {
            throw new IllegalArgumentException("bytesToAdd must be positive: " + bytesToAdd);
        }
        AtomicInteger credits = streamCredits.computeIfAbsent(streamId, id -> new AtomicInteger(streamInitialWindow));
        return credits.addAndGet(bytesToAdd);
    }

    /**
     * Replenishes connection-level credits.
     */
    public int addConnectionCredits(int bytesToAdd) {
        if (bytesToAdd <= 0) {
            throw new IllegalArgumentException("bytesToAdd must be positive: " + bytesToAdd);
        }
        return connectionCredits.updateAndGet(current -> Math.min(connectionMaxWindow, current + bytesToAdd));
    }

    /**
     * Checks if both the stream and connection currently have sufficient credits to transmit {@code bytes}.
     */
    public boolean canSend(int streamId, int bytes) {
        if (bytes <= 0) {
            return true;
        }
        if (connectionCredits.get() < bytes) {
            return false;
        }
        AtomicInteger stream = streamCredits.get(streamId);
        return stream != null && stream.get() >= bytes;
    }

    /**
     * Consumes credits from both the stream and connection windows.
     *
     * @param streamId the target stream
     * @param bytes    number of payload bytes to consume
     * @return true if credits were successfully reserved, false if windows were insufficient
     */
    public synchronized boolean tryConsume(int streamId, int bytes) {
        if (bytes <= 0) {
            return true;
        }
        AtomicInteger stream = streamCredits.get(streamId);
        if (stream == null) {
            return false;
        }
        if (connectionCredits.get() < bytes || stream.get() < bytes) {
            return false;
        }
        connectionCredits.addAndGet(-bytes);
        stream.addAndGet(-bytes);
        return true;
    }

    /** Available credits on a stream, or 0 if stream not found. */
    public int streamCredits(int streamId) {
        AtomicInteger stream = streamCredits.get(streamId);
        return stream == null ? 0 : stream.get();
    }

    /** Available connection-level credits. */
    public int connectionCredits() {
        return connectionCredits.get();
    }
}
