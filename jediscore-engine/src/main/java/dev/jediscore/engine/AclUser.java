package dev.jediscore.engine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * An ACL user: enabled state, passwords (SHA-256), key/channel patterns, and
 * command permissions. A deliberately <em>basic</em> ACL — command-level rules
 * (and the {@code @read}/{@code @write}/{@code @admin}/{@code @all} categories) are
 * enforced; key/channel patterns are parsed, stored, and reported but not enforced
 * at the dispatcher yet (documented in COMPATIBILITY.md).
 */
public final class AclUser {

    /** A small admin/dangerous command set for the {@code @admin}/{@code @dangerous} categories. */
    private static final Set<String> ADMIN = Set.of(
            "CONFIG", "DEBUG", "SHUTDOWN", "MONITOR", "SLAVEOF", "REPLICAOF", "ACL", "CLIENT",
            "FLUSHALL", "FLUSHDB", "SAVE", "BGSAVE", "BGREWRITEAOF", "SLOWLOG", "LATENCY", "RESET",
            "FAILOVER", "SWAPDB", "PSYNC", "SYNC", "REPLCONF");

    private final String name;
    private boolean enabled;
    private boolean nopass;
    private final Set<String> passwordHashes = new LinkedHashSet<>();
    private boolean allKeys;
    private final List<String> keyPatterns = new ArrayList<>();
    private boolean allChannels;
    private final List<String> channelPatterns = new ArrayList<>();
    private boolean allCommands;
    private final Set<String> plusCommands = new LinkedHashSet<>();
    private final Set<String> minusCommands = new LinkedHashSet<>();
    private final Set<String> plusCategories = new LinkedHashSet<>();
    private final Set<String> minusCategories = new LinkedHashSet<>();

    /**
     * Creates a disabled, no-permission user (Redis's default for a new user).
     *
     * @param name the username
     */
    public AclUser(String name) {
        this.name = name;
    }

    /** @return the username */
    public String name() {
        return name;
    }

    /** @return whether the user is enabled (can authenticate) */
    public boolean isEnabled() {
        return enabled;
    }

    /** @return whether the user authenticates without a password */
    public boolean isNopass() {
        return nopass;
    }

    /**
     * Verifies a password against this user.
     *
     * @param plain the supplied password
     * @return {@code true} if it matches (or the user is {@code nopass})
     */
    public boolean checkPassword(String plain) {
        return nopass || passwordHashes.contains(sha256(plain));
    }

    /**
     * Tests whether this user may run a command.
     *
     * @param commandUpper the upper-cased command name
     * @return {@code true} if permitted
     */
    public boolean canRun(String commandUpper) {
        if (minusCommands.contains(commandUpper)) {
            return false;
        }
        if (plusCommands.contains(commandUpper)) {
            return true;
        }
        boolean allowed = allCommands;
        for (String cat : plusCategories) {
            if (categoryContains(cat, commandUpper)) {
                allowed = true;
                break;
            }
        }
        for (String cat : minusCategories) {
            if (categoryContains(cat, commandUpper)) {
                allowed = false;
            }
        }
        return allowed;
    }

    /** @return whether the user may access every key ({@code ~*}) */
    public boolean allKeys() {
        return allKeys;
    }

    /**
     * Tests whether this user may access a key, by glob-matching its key patterns.
     *
     * @param key the key bytes
     * @return {@code true} if permitted
     */
    public boolean canAccessKey(byte[] key) {
        if (allKeys) {
            return true;
        }
        for (String pattern : keyPatterns) {
            if (dev.jediscore.datastructures.Glob.match(pattern.getBytes(StandardCharsets.UTF_8), key)) {
                return true;
            }
        }
        return false;
    }

    private static boolean categoryContains(String category, String commandUpper) {
        return switch (category) {
            case "all" -> true;
            case "write" -> WriteCommands.isWrite(commandUpper);
            case "read" -> !WriteCommands.isWrite(commandUpper) && !ADMIN.contains(commandUpper);
            case "admin", "dangerous" -> ADMIN.contains(commandUpper);
            default -> false; // other categories accepted but not finely mapped
        };
    }

    /**
     * Applies one {@code ACL SETUSER} rule token.
     *
     * @param rule the rule (e.g. {@code on}, {@code >pw}, {@code ~*}, {@code +get}, {@code +@read})
     */
    public void applyRule(String rule) {
        String lower = rule.toLowerCase(Locale.ROOT);
        switch (lower) {
            case "on" -> enabled = true;
            case "off" -> enabled = false;
            case "nopass" -> { nopass = true; passwordHashes.clear(); }
            case "resetpass" -> { nopass = false; passwordHashes.clear(); }
            case "allkeys", "~*" -> { allKeys = true; keyPatterns.clear(); }
            case "resetkeys" -> { allKeys = false; keyPatterns.clear(); }
            case "allchannels", "&*" -> { allChannels = true; channelPatterns.clear(); }
            case "resetchannels" -> { allChannels = false; channelPatterns.clear(); }
            case "allcommands", "+@all" -> { allCommands = true; plusCommands.clear(); minusCommands.clear();
                                             plusCategories.clear(); minusCategories.clear(); }
            case "nocommands", "-@all" -> { allCommands = false; plusCommands.clear(); minusCommands.clear();
                                            plusCategories.clear(); minusCategories.clear(); }
            case "reset" -> reset();
            default -> applyPrefixedRule(rule, lower);
        }
    }

    private void applyPrefixedRule(String rule, String lower) {
        if (rule.startsWith(">")) {
            nopass = false;
            passwordHashes.add(sha256(rule.substring(1)));
        } else if (rule.startsWith("<")) {
            passwordHashes.remove(sha256(rule.substring(1)));
        } else if (rule.startsWith("#")) {
            nopass = false;
            passwordHashes.add(rule.substring(1).toLowerCase(Locale.ROOT));
        } else if (rule.startsWith("~")) {
            keyPatterns.add(rule.substring(1));
        } else if (rule.startsWith("&")) {
            channelPatterns.add(rule.substring(1));
        } else if (lower.startsWith("+@")) {
            String cat = lower.substring(2);
            minusCategories.remove(cat);
            plusCategories.add(cat);
        } else if (lower.startsWith("-@")) {
            String cat = lower.substring(2);
            plusCategories.remove(cat);
            minusCategories.add(cat);
        } else if (rule.startsWith("+")) {
            String cmd = lower.substring(1).toUpperCase(Locale.ROOT);
            minusCommands.remove(cmd);
            plusCommands.add(cmd);
        } else if (rule.startsWith("-")) {
            String cmd = lower.substring(1).toUpperCase(Locale.ROOT);
            plusCommands.remove(cmd);
            minusCommands.add(cmd);
        } else {
            throw new CommandException("ERR Error in ACL SETUSER modifier '" + rule + "'");
        }
    }

    /** Resets the user to the default empty/disabled state. */
    public void reset() {
        enabled = false;
        nopass = false;
        passwordHashes.clear();
        allKeys = false;
        keyPatterns.clear();
        allChannels = false;
        channelPatterns.clear();
        allCommands = false;
        plusCommands.clear();
        minusCommands.clear();
        plusCategories.clear();
        minusCategories.clear();
    }

    /** Convenience: configure the all-powerful {@code default} user. */
    public void makeSuperuser(boolean nopass) {
        this.enabled = true;
        this.nopass = nopass;
        this.allKeys = true;
        this.allChannels = true;
        this.allCommands = true;
    }

    /**
     * Sets the default user's password (from {@code requirepass}).
     *
     * @param password the plain password, or {@code null} to clear (then {@code nopass})
     */
    public void setPlainPassword(String password) {
        passwordHashes.clear();
        if (password == null || password.isEmpty()) {
            nopass = true;
        } else {
            nopass = false;
            passwordHashes.add(sha256(password));
        }
    }

    /** @return the ACL rule string, as {@code ACL LIST}/{@code GETUSER} report it */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(enabled ? "on" : "off");
        if (nopass) {
            sb.append(" nopass");
        }
        for (String hash : passwordHashes) {
            sb.append(" #").append(hash);
        }
        sb.append(allKeys ? " ~*" : "");
        for (String p : keyPatterns) {
            sb.append(" ~").append(p);
        }
        sb.append(allChannels ? " &*" : (channelPatterns.isEmpty() ? " resetchannels" : ""));
        for (String p : channelPatterns) {
            sb.append(" &").append(p);
        }
        if (allCommands) {
            sb.append(" +@all");
        } else {
            sb.append(" -@all");
            for (String cat : plusCategories) {
                sb.append(" +@").append(cat);
            }
            for (String cmd : plusCommands) {
                sb.append(" +").append(cmd.toLowerCase(Locale.ROOT));
            }
        }
        for (String cmd : minusCommands) {
            sb.append(" -").append(cmd.toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    /** @return the configured key patterns (or {@code ~*} when allKeys) */
    public List<String> keyPatterns() {
        return allKeys ? List.of("~*") : keyPatterns;
    }

    private static String sha256(String plain) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(plain.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xf, 16));
                sb.append(Character.forDigit(b & 0xf, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
