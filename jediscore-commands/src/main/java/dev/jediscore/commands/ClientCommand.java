package dev.jediscore.commands;

import dev.jediscore.engine.ClientConnection;
import dev.jediscore.engine.Command;
import dev.jediscore.engine.CommandContext;
import dev.jediscore.protocol.RespValue;

/**
 * {@code CLIENT <subcommand>} — connection introspection and control.
 *
 * <p>Phase-1 supports the subset clients actually use on connect:
 * {@code ID}, {@code GETNAME}, {@code SETNAME}, {@code INFO}, and {@code SETINFO}
 * (the last is sent by Jedis/Lettuce to advertise their library name/version and
 * is simply accepted).
 */
public final class ClientCommand implements Command {

    @Override
    public RespValue execute(CommandContext ctx) {
        String sub = ctx.argUpper(1);
        ClientConnection conn = ctx.connection();
        conn.setLastCommand("client|" + sub.toLowerCase(java.util.Locale.ROOT));
        return switch (sub) {
            case "ID" -> RespValue.integer(conn.id());
            case "GETNAME" -> RespValue.bulk(conn.name());
            case "SETNAME" -> setName(ctx, conn);
            case "INFO" -> RespValue.bulk(infoLine(conn));
            case "SETINFO" -> setInfo(ctx);
            default -> RespValue.error(
                    "ERR Unknown CLIENT subcommand or wrong number of arguments for '"
                            + ctx.argText(1) + "'. Try CLIENT HELP.");
        };
    }

    private static RespValue setName(CommandContext ctx, ClientConnection conn) {
        if (ctx.argCount() != 3) {
            return RespValue.error("ERR wrong number of arguments for 'client|setname' command");
        }
        String name = ctx.argText(2);
        // Redis forbids spaces and newlines so the name is safe to print in CLIENT LIST.
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x21 || c > 0x7e) {
                return RespValue.error(
                        "ERR Client names cannot contain spaces, newlines or special characters.");
            }
        }
        conn.setName(name);
        return RespValue.OK;
    }

    private static RespValue setInfo(CommandContext ctx) {
        // CLIENT SETINFO <attr> <value>; we accept lib-name/lib-ver advertisements.
        if (ctx.argCount() != 4) {
            return RespValue.error("ERR wrong number of arguments for 'client|setinfo' command");
        }
        return RespValue.OK;
    }

    /**
     * Builds the single-line {@code CLIENT INFO} description. A representative
     * subset of Redis's fields — enough for clients and humans, without
     * fabricating buffer/memory counters we don't track yet.
     */
    private static String infoLine(ClientConnection conn) {
        return "id=" + conn.id()
                + " addr=" + conn.remoteAddress()
                + " laddr=" + conn.localAddress()
                + " name=" + conn.name()
                + " age=" + conn.ageSeconds()
                + " db=0"
                + " resp=" + conn.protocol().wireNumber()
                + " cmd=" + conn.lastCommand();
    }
}
