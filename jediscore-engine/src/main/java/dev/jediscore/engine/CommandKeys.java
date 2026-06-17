package dev.jediscore.engine;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The command key-spec table: which argument positions of a command are keys.
 * Shared by {@code COMMAND GETKEYS}/{@code INFO} and by ACL key-pattern
 * enforcement, so the two agree.
 *
 * <p>A best-effort model (documented in COMPATIBILITY.md): a default of
 * {@code (first=1, last=1, step=1)} — correct for the vast majority of commands —
 * with explicit no-key and multi-key entries, and {@code EVAL}/{@code EVALSHA}
 * resolved via their {@code numkeys} argument. It is not Redis's full flag-rich
 * key-spec system.
 */
public final class CommandKeys {

    private static final Set<String> NO_KEY = Set.of(
            "PING", "ECHO", "HELLO", "AUTH", "QUIT", "RESET", "SELECT", "SWAPDB", "COMMAND", "INFO",
            "ROLE", "REPLICAOF", "SLAVEOF", "CONFIG", "DBSIZE", "FLUSHDB", "FLUSHALL", "DEBUG",
            "SLOWLOG", "LATENCY", "MONITOR", "CLIENT", "SAVE", "BGSAVE", "BGREWRITEAOF", "LASTSAVE",
            "MULTI", "EXEC", "DISCARD", "UNWATCH", "WAIT", "SCAN", "RANDOMKEY", "SCRIPT", "PUBLISH",
            "SUBSCRIBE", "UNSUBSCRIBE", "PSUBSCRIBE", "PUNSUBSCRIBE", "PUBSUB", "SPUBLISH",
            "SSUBSCRIBE", "SUNSUBSCRIBE", "REPLCONF", "PSYNC", "SYNC", "SENTINEL", "FAILOVER", "ACL",
            "SHUTDOWN");

    /** Keys span {@code args[first..last]} with the given step; {@code last == -1} means "to the end". */
    private static final Map<String, int[]> MULTI_KEY = Map.ofEntries(
            Map.entry("MSET", new int[]{1, -1, 2}),
            Map.entry("MSETNX", new int[]{1, -1, 2}),
            Map.entry("MGET", new int[]{1, -1, 1}),
            Map.entry("DEL", new int[]{1, -1, 1}),
            Map.entry("UNLINK", new int[]{1, -1, 1}),
            Map.entry("EXISTS", new int[]{1, -1, 1}),
            Map.entry("WATCH", new int[]{1, -1, 1}),
            Map.entry("SINTER", new int[]{1, -1, 1}),
            Map.entry("SUNION", new int[]{1, -1, 1}),
            Map.entry("SDIFF", new int[]{1, -1, 1}),
            Map.entry("PFCOUNT", new int[]{1, -1, 1}),
            Map.entry("RENAME", new int[]{1, 2, 1}),
            Map.entry("RENAMENX", new int[]{1, 2, 1}),
            Map.entry("COPY", new int[]{1, 2, 1}),
            Map.entry("SMOVE", new int[]{1, 2, 1}),
            Map.entry("LMOVE", new int[]{1, 2, 1}),
            Map.entry("RPOPLPUSH", new int[]{1, 2, 1}),
            Map.entry("BLMOVE", new int[]{1, 2, 1}),
            Map.entry("BRPOPLPUSH", new int[]{1, 2, 1}));

    private CommandKeys() {
        // Static utility; not instantiable.
    }

    /** @return the first key position (1-based), or 0 if the command has no keys */
    public static int firstKey(String upperName) {
        if (NO_KEY.contains(upperName)) {
            return 0;
        }
        int[] spec = MULTI_KEY.get(upperName);
        return spec == null ? 1 : spec[0];
    }

    /** @return the last key position ({@code -1} = "to the end"), or 0 if no keys */
    public static int lastKey(String upperName) {
        if (NO_KEY.contains(upperName)) {
            return 0;
        }
        int[] spec = MULTI_KEY.get(upperName);
        return spec == null ? 1 : spec[1];
    }

    /** @return the step between key positions, or 0 if no keys */
    public static int keyStep(String upperName) {
        if (NO_KEY.contains(upperName)) {
            return 0;
        }
        int[] spec = MULTI_KEY.get(upperName);
        return spec == null ? 1 : spec[2];
    }

    /**
     * Extracts the keys a command touches.
     *
     * @param args the command argument vector ({@code args[0]} is the command name)
     * @return the key byte-arrays (possibly empty)
     */
    public static List<byte[]> extractKeys(byte[][] args) {
        if (args.length == 0) {
            return List.of();
        }
        String name = new String(args[0], StandardCharsets.UTF_8).toUpperCase(Locale.ROOT);
        List<byte[]> keys = new ArrayList<>();
        if (name.equals("EVAL") || name.equals("EVALSHA") || name.equals("FCALL") || name.equals("FCALL_RO")) {
            if (args.length >= 3) {
                long numKeys = parseLong(args[2]);
                for (int i = 0; i < numKeys && 3 + i < args.length; i++) {
                    keys.add(args[3 + i]);
                }
            }
            return keys;
        }
        int first = firstKey(name);
        if (first == 0) {
            return keys;
        }
        int last = lastKey(name);
        int step = keyStep(name);
        int resolvedLast = last < 0 ? args.length - 1 + (last + 1) : last; // -1 → last arg
        for (int i = first; i <= resolvedLast && i < args.length; i += step) {
            keys.add(args[i]);
        }
        return keys;
    }

    private static long parseLong(byte[] bytes) {
        try {
            return Long.parseLong(new String(bytes, StandardCharsets.US_ASCII).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
