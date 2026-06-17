package dev.jediscore.commands;

import dev.jediscore.engine.AclRegistry;
import dev.jediscore.engine.AclUser;
import dev.jediscore.engine.CommandContext;
import dev.jediscore.engine.CommandException;
import dev.jediscore.engine.CommandRegistry;
import dev.jediscore.engine.CommandSpec;
import dev.jediscore.protocol.RespValue;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code ACL} — a basic access-control list: {@code WHOAMI}, {@code LIST},
 * {@code USERS}, {@code CAT}, {@code GETUSER}, {@code SETUSER}, {@code DELUSER},
 * {@code GENPASS}.
 *
 * <p>Honest scope: command-level rules and the {@code @read}/{@code @write}/
 * {@code @admin}/{@code @all} categories are enforced by the dispatcher; key and
 * channel patterns are parsed, stored, and reported but not yet enforced.
 */
public final class AclCommands {

    private static final List<String> CATEGORIES = List.of(
            "keyspace", "read", "write", "set", "sortedset", "list", "hash", "string", "bitmap",
            "hyperloglog", "geo", "stream", "pubsub", "admin", "fast", "slow", "blocking",
            "dangerous", "connection", "transaction", "scripting");

    private static final SecureRandom RANDOM = new SecureRandom();

    private AclCommands() {
        // Static utility; not instantiable.
    }

    /**
     * Registers {@code ACL}.
     *
     * @param registry the registry to populate
     */
    public static void registerAll(CommandRegistry registry) {
        registry.register(CommandSpec.of("acl", -2, AclCommands::acl));
    }

    private static RespValue acl(CommandContext ctx) {
        AclRegistry acl = ctx.server().acl();
        String sub = ctx.argUpper(1);
        switch (sub) {
            case "WHOAMI" -> {
                return RespValue.bulk(ctx.connection().user());
            }
            case "LIST" -> {
                List<RespValue> out = new ArrayList<>();
                for (AclUser user : acl.all()) {
                    out.add(RespValue.bulk("user " + user.name() + " " + user.describe()));
                }
                return new RespValue.Array(out);
            }
            case "USERS" -> {
                List<RespValue> out = new ArrayList<>();
                for (String name : acl.usernames()) {
                    out.add(RespValue.bulk(name));
                }
                return new RespValue.Array(out);
            }
            case "CAT" -> {
                List<RespValue> out = new ArrayList<>();
                for (String cat : CATEGORIES) {
                    out.add(RespValue.bulk(cat));
                }
                return new RespValue.Array(out);
            }
            case "GETUSER" -> {
                if (ctx.argCount() < 3) {
                    throw new CommandException("ERR wrong number of arguments for 'acl|getuser' command");
                }
                AclUser user = acl.user(ctx.argText(2));
                if (user == null) {
                    return RespValue.NULL;
                }
                return getUser(user);
            }
            case "SETUSER" -> {
                if (ctx.argCount() < 3) {
                    throw new CommandException("ERR wrong number of arguments for 'acl|setuser' command");
                }
                AclUser user = acl.getOrCreate(ctx.argText(2));
                for (int i = 3; i < ctx.argCount(); i++) {
                    user.applyRule(ctx.argText(i));
                }
                return RespValue.OK;
            }
            case "DELUSER" -> {
                int deleted = 0;
                for (int i = 2; i < ctx.argCount(); i++) {
                    if ("default".equals(ctx.argText(i))) {
                        throw new CommandException("ERR The 'default' user cannot be removed");
                    }
                    if (acl.delete(ctx.argText(i))) {
                        deleted++;
                    }
                }
                return RespValue.integer(deleted);
            }
            case "GENPASS" -> {
                int bits = ctx.argCount() > 2 ? (int) Keyspaces.parseLong(ctx.arg(2)) : 256;
                return RespValue.bulk(genpass(bits));
            }
            case "HELP" -> {
                return new RespValue.Array(List.of(
                        RespValue.simple("ACL WHOAMI / LIST / USERS / CAT"),
                        RespValue.simple("ACL GETUSER <name> / SETUSER <name> <rules...> / DELUSER <name>"),
                        RespValue.simple("ACL GENPASS [bits]")));
            }
            default -> throw new CommandException(
                    "ERR Unknown ACL subcommand or wrong number of arguments for '" + ctx.argText(1) + "'");
        }
    }

    private static RespValue getUser(AclUser user) {
        List<RespValue> flags = new ArrayList<>();
        flags.add(RespValue.bulk(user.isEnabled() ? "on" : "off"));
        if (user.isNopass()) {
            flags.add(RespValue.bulk("nopass"));
        }
        List<RespValue> keys = new ArrayList<>();
        for (String pattern : user.keyPatterns()) {
            keys.add(RespValue.bulk(pattern));
        }
        return new RespValue.Array(List.of(
                RespValue.bulk("flags"), new RespValue.Array(flags),
                RespValue.bulk("passwords"), new RespValue.Array(List.of()),
                RespValue.bulk("commands"), RespValue.bulk(commandsRule(user)),
                RespValue.bulk("keys"), new RespValue.Array(keys),
                RespValue.bulk("channels"), new RespValue.Array(List.of())));
    }

    /** Extracts just the command portion of the user's rule string. */
    private static String commandsRule(AclUser user) {
        String desc = user.describe();
        int at = desc.indexOf("@all");
        return at < 0 ? "-@all" : desc.substring(desc.lastIndexOf(' ', at) + 1);
    }

    private static String genpass(int bits) {
        int hexChars = Math.max(1, (bits + 3) / 4);
        StringBuilder sb = new StringBuilder(hexChars);
        for (int i = 0; i < hexChars; i++) {
            sb.append(Character.forDigit(RANDOM.nextInt(16), 16));
        }
        return sb.toString();
    }
}
