package dev.jediscore.engine;

/**
 * A fixed-capacity ring buffer of the most recent replication-stream bytes,
 * mirroring Redis's {@code repl_backlog}. It lets a briefly-disconnected replica
 * resume with a partial resync (served in Phase 6C) instead of a full resync,
 * provided its offset is still within the retained window.
 *
 * <p>Command-thread confined (appended during propagation, read during a partial
 * resync), so it needs no synchronization.
 */
public final class ReplicationBacklog {

    /** Default backlog size: 1 MiB, matching Redis's default {@code repl-backlog-size}. */
    private static final int DEFAULT_CAPACITY = 1024 * 1024;

    private final byte[] buffer;
    private int head;        // index of the next write
    private long startOffset; // master offset of the oldest retained byte
    private long endOffset;   // master offset just past the newest retained byte
    private boolean active;

    /** Creates a backlog with the default capacity. */
    public ReplicationBacklog() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * Creates a backlog with a given capacity.
     *
     * @param capacity the ring size in bytes
     */
    public ReplicationBacklog(int capacity) {
        this.buffer = new byte[capacity];
    }

    /** @return whether the backlog has received any data */
    public boolean isActive() {
        return active;
    }

    /** @return the master offset of the oldest byte still retained */
    public long startOffset() {
        return startOffset;
    }

    /** @return the master offset just past the newest retained byte */
    public long endOffset() {
        return endOffset;
    }

    /**
     * Appends bytes to the ring, evicting the oldest as needed.
     *
     * @param bytes the bytes to append
     */
    public void append(byte[] bytes) {
        if (!active) {
            active = true;
        }
        for (byte b : bytes) {
            buffer[head] = b;
            head = (head + 1) % buffer.length;
        }
        endOffset += bytes.length;
        long retained = Math.min(endOffset, buffer.length);
        startOffset = endOffset - retained;
    }

    /**
     * Returns whether the given offset can be served from the backlog (i.e. the
     * bytes from {@code offset} onward are still retained).
     *
     * @param offset the requested resume offset
     * @return {@code true} if a partial resync from {@code offset} is possible
     */
    public boolean canServe(long offset) {
        return active && offset >= startOffset && offset <= endOffset;
    }

    /**
     * Copies the retained bytes from {@code offset} to the end of the stream.
     *
     * @param offset the resume offset (must satisfy {@link #canServe})
     * @return the bytes the replica is missing
     */
    public byte[] since(long offset) {
        int length = (int) (endOffset - offset);
        byte[] out = new byte[length];
        int distanceFromEnd = (int) (endOffset - offset);
        int start = ((head - distanceFromEnd) % buffer.length + buffer.length) % buffer.length;
        for (int i = 0; i < length; i++) {
            out[i] = buffer[(start + i) % buffer.length];
        }
        return out;
    }
}
