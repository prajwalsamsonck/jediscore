package dev.jediscore.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The slow-command log: a bounded, newest-first ring of commands whose execution
 * exceeded {@code slowlog-log-slower-than} microseconds, mirroring Redis's
 * {@code SLOWLOG}.
 *
 * <p>Command-thread-confined: recorded from the dispatcher and read by
 * {@code SLOWLOG GET/LEN}, all on the command thread, so no locking.
 */
public final class SlowLog {

    /** Redis caps a logged command at 32 arguments and each argument at 128 bytes. */
    private static final int MAX_ARGS = 32;
    private static final int MAX_ARG_BYTES = 128;

    /** One logged slow command. */
    public record Entry(long id, long timeSeconds, long durationMicros,
                        List<byte[]> args, String clientAddr, String clientName) { }

    private final Deque<Entry> entries = new ArrayDeque<>();
    private long nextId;
    private int maxLen = 128;
    private long thresholdMicros = 10_000; // 10ms; -1 disables, 0 logs everything

    /**
     * Records a command if it was slow enough.
     *
     * @param durationMicros the command's execution time in microseconds
     * @param ctx            the command context (for the args and client)
     */
    public void maybeRecord(long durationMicros, CommandContext ctx) {
        if (thresholdMicros < 0 || durationMicros < thresholdMicros) {
            return;
        }
        ClientConnection conn = ctx.connection();
        entries.addFirst(new Entry(nextId++, System.currentTimeMillis() / 1000, durationMicros,
                truncateArgs(ctx), conn.remoteAddress(), conn.name()));
        while (entries.size() > maxLen) {
            entries.removeLast();
        }
    }

    /**
     * @param count the maximum number of entries (negative for all)
     * @return the most recent entries, newest first
     */
    public List<Entry> get(int count) {
        int limit = count < 0 ? entries.size() : Math.min(count, entries.size());
        List<Entry> out = new ArrayList<>(limit);
        int i = 0;
        for (Entry e : entries) {
            if (i++ >= limit) {
                break;
            }
            out.add(e);
        }
        return out;
    }

    /** @return the number of entries currently logged */
    public int length() {
        return entries.size();
    }

    /** Clears the log. */
    public void reset() {
        entries.clear();
    }

    /** @return the slow threshold in microseconds */
    public long thresholdMicros() {
        return thresholdMicros;
    }

    /**
     * Sets the slow threshold ({@code slowlog-log-slower-than}).
     *
     * @param micros microseconds; {@code -1} disables, {@code 0} logs everything
     */
    public void setThresholdMicros(long micros) {
        this.thresholdMicros = micros;
    }

    /** @return the maximum number of retained entries */
    public int maxLen() {
        return maxLen;
    }

    /**
     * Sets the maximum number of retained entries ({@code slowlog-max-len}).
     *
     * @param maxLen the cap
     */
    public void setMaxLen(int maxLen) {
        this.maxLen = Math.max(0, maxLen);
        while (entries.size() > this.maxLen) {
            entries.removeLast();
        }
    }

    private static List<byte[]> truncateArgs(CommandContext ctx) {
        int argc = ctx.argCount();
        int kept = Math.min(argc, MAX_ARGS);
        boolean truncatedArgc = argc > MAX_ARGS;
        List<byte[]> out = new ArrayList<>(kept);
        int limit = truncatedArgc ? MAX_ARGS - 1 : kept;
        for (int i = 0; i < limit; i++) {
            out.add(truncateArg(ctx.arg(i)));
        }
        if (truncatedArgc) {
            out.add(("... (" + (argc - (MAX_ARGS - 1)) + " more arguments)")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return out;
    }

    private static byte[] truncateArg(byte[] arg) {
        if (arg.length <= MAX_ARG_BYTES) {
            return arg;
        }
        String suffix = "... (" + (arg.length - MAX_ARG_BYTES) + " more bytes)";
        byte[] suffixBytes = suffix.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] out = new byte[MAX_ARG_BYTES + suffixBytes.length];
        System.arraycopy(arg, 0, out, 0, MAX_ARG_BYTES);
        System.arraycopy(suffixBytes, 0, out, MAX_ARG_BYTES, suffixBytes.length);
        return out;
    }
}
