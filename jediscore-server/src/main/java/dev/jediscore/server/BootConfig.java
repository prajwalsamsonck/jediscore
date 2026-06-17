package dev.jediscore.server;

import dev.jediscore.commands.ConfigCommands;
import dev.jediscore.engine.MaxmemoryPolicy;
import dev.jediscore.engine.PersistenceConfig;
import dev.jediscore.engine.SavePoint;
import dev.jediscore.engine.ServerConfig;
import dev.jediscore.engine.TlsConfig;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Startup configuration, loaded from a redis.conf-style file and/or CLI
 * {@code --option value} flags, the way {@code redis-server [config] [--opt …]}
 * works. CLI options override file directives.
 *
 * @param server         the resulting server configuration
 * @param persistence    the resulting persistence configuration
 * @param configFile     the loaded config-file path, or {@code null} if none
 * @param maxClients     the maximum concurrent clients
 * @param protectedMode  whether protected mode is enabled
 * @param renameCommands {@code rename-command} directives ({@code from → to}, empty = disable)
 * @param tls            the TLS configuration
 * @param metricsPort    the Prometheus metrics HTTP port ({@code 0} = disabled)
 */
public record BootConfig(ServerConfig server, PersistenceConfig persistence, String configFile,
                         int maxClients, boolean protectedMode, Map<String, String> renameCommands,
                         TlsConfig tls, int metricsPort) {

    /**
     * Loads configuration from the command-line arguments.
     *
     * @param args the arguments ({@code [config-file] [host:port] [--opt value …]})
     * @return the loaded configuration
     */
    public static BootConfig load(String[] args) {
        Map<String, List<String>> params = new LinkedHashMap<>();
        Map<String, String> renames = new LinkedHashMap<>();
        String configFile = null;
        String address = null;
        int i = 0;
        if (args.length > 0 && !args[0].startsWith("--") && isConfigFile(args[0])) {
            configFile = args[0];
            parseFile(configFile, params, renames);
            i = 1;
        }
        for (; i < args.length; ) {
            String a = args[i];
            if (a.startsWith("--")) {
                String key = a.substring(2).toLowerCase(Locale.ROOT);
                List<String> values = new ArrayList<>();
                i++;
                while (i < args.length && !args[i].startsWith("--")) {
                    values.add(args[i]);
                    i++;
                }
                if (key.equals("rename-command") && values.size() == 2) {
                    renames.put(values.get(0).toUpperCase(Locale.ROOT), values.get(1));
                } else {
                    params.put(key, values);
                }
            } else {
                address = a; // host:port positional
                i++;
            }
        }
        return build(params, address, configFile, renames);
    }

    private static boolean isConfigFile(String arg) {
        if (arg.isBlank()) {
            return false;
        }
        if (arg.endsWith(".conf")) {
            return true; // also catches Windows paths like C:\...\redis.conf
        }
        try {
            return Files.exists(Path.of(arg));
        } catch (java.nio.file.InvalidPathException e) {
            return false; // e.g. "host:port" is not a valid path on Windows
        }
    }

    private static void parseFile(String path, Map<String, List<String>> params, Map<String, String> renames) {
        try {
            for (String raw : Files.readAllLines(Path.of(path))) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                List<String> tokens = tokenize(line);
                if (tokens.isEmpty()) {
                    continue;
                }
                String key = tokens.get(0).toLowerCase(Locale.ROOT);
                List<String> values = tokens.subList(1, tokens.size());
                if (key.equals("rename-command") && values.size() == 2) {
                    renames.put(values.get(0).toUpperCase(Locale.ROOT), values.get(1)); // accumulate
                } else {
                    params.put(key, values);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read config file: " + path, e);
        }
    }

    /** Splits a directive on whitespace, honouring double-quoted tokens. */
    private static List<String> tokenize(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        boolean has = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
                has = true;
            } else if (Character.isWhitespace(c) && !inQuotes) {
                if (has) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    has = false;
                }
            } else {
                cur.append(c);
                has = true;
            }
        }
        if (has) {
            out.add(cur.toString());
        }
        return out;
    }

    private static BootConfig build(Map<String, List<String>> params, String address, String configFile,
                                    Map<String, String> renames) {
        String host = first(params, "bind", "127.0.0.1");
        int port = Integer.parseInt(first(params, "port", "6379"));
        if (address != null && !address.isBlank()) {
            int colon = address.lastIndexOf(':');
            if (colon >= 0) {
                host = address.substring(0, colon);
                port = Integer.parseInt(address.substring(colon + 1));
            } else {
                port = Integer.parseInt(address);
            }
        }

        ServerConfig.Builder b = ServerConfig.defaults(host, port).toBuilder();
        apply(params, "databases", v -> b.databases(Integer.parseInt(v)));
        apply(params, "maxmemory", v -> b.maxMemory(ConfigCommands.parseMemory(v)));
        apply(params, "maxmemory-policy", v -> b.maxMemoryPolicy(MaxmemoryPolicy.fromConfig(v)));
        apply(params, "maxmemory-samples", v -> b.maxMemorySamples(Integer.parseInt(v)));
        apply(params, "requirepass", v -> b.requirepass(v.isEmpty() ? Optional.empty() : Optional.of(v)));
        apply(params, "hash-max-listpack-entries", v -> b.hashMaxListpackEntries(Integer.parseInt(v)));
        apply(params, "hash-max-listpack-value", v -> b.hashMaxListpackValue(Integer.parseInt(v)));
        apply(params, "list-max-listpack-size", v -> b.listMaxListpackSize(Integer.parseInt(v)));
        apply(params, "set-max-intset-entries", v -> b.setMaxIntsetEntries(Integer.parseInt(v)));
        apply(params, "set-max-listpack-entries", v -> b.setMaxListpackEntries(Integer.parseInt(v)));
        apply(params, "set-max-listpack-value", v -> b.setMaxListpackValue(Integer.parseInt(v)));
        apply(params, "zset-max-listpack-entries", v -> b.zsetMaxListpackEntries(Integer.parseInt(v)));
        apply(params, "zset-max-listpack-value", v -> b.zsetMaxListpackValue(Integer.parseInt(v)));
        ServerConfig serverConfig = b.build();

        PersistenceConfig pc = PersistenceConfig.defaults();
        if (params.containsKey("dir")) {
            pc = pc.withDir(first(params, "dir", "."));
        }
        if (params.containsKey("save")) {
            pc = pc.withSavePoints(parseSavePoints(params.get("save")));
        }
        if ("yes".equalsIgnoreCase(first(params, "appendonly", "no"))) {
            pc = pc.withAppendOnly(first(params, "appendfsync", "everysec"));
        }
        int maxClients = Integer.parseInt(first(params, "maxclients", "10000"));
        boolean protectedMode = !"no".equalsIgnoreCase(first(params, "protected-mode", "yes"));
        TlsConfig tls = new TlsConfig(
                "yes".equalsIgnoreCase(first(params, "tls", "no")),
                params.containsKey("tls-cert-file") ? first(params, "tls-cert-file", null) : null,
                params.containsKey("tls-key-file") ? first(params, "tls-key-file", null) : null);
        int metricsPort = Integer.parseInt(first(params, "metrics-port", "0"));
        return new BootConfig(serverConfig, pc, configFile, maxClients, protectedMode, renames, tls, metricsPort);
    }

    private static List<SavePoint> parseSavePoints(List<String> values) {
        List<SavePoint> points = new ArrayList<>();
        // "save" with a single empty token disables RDB saving.
        if (values.size() == 1 && values.get(0).isEmpty()) {
            return points;
        }
        for (int i = 0; i + 1 < values.size(); i += 2) {
            points.add(new SavePoint(Long.parseLong(values.get(i)), Long.parseLong(values.get(i + 1))));
        }
        return points;
    }

    private static String first(Map<String, List<String>> params, String key, String fallback) {
        List<String> values = params.get(key);
        return (values == null || values.isEmpty()) ? fallback : values.get(0);
    }

    private static void apply(Map<String, List<String>> params, String key, java.util.function.Consumer<String> setter) {
        List<String> values = params.get(key);
        if (values != null && !values.isEmpty()) {
            setter.accept(values.get(0));
        }
    }
}
