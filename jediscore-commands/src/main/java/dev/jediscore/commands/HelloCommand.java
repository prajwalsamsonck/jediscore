package dev.jediscore.commands;

import dev.jediscore.engine.ClientConnection;
import dev.jediscore.engine.Command;
import dev.jediscore.engine.CommandContext;
import dev.jediscore.engine.ServerContext;
import dev.jediscore.protocol.RespValue;
import dev.jediscore.protocol.RespVersion;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code HELLO [protover [AUTH user pass] [SETNAME name]]} — handshake and
 * protocol negotiation.
 *
 * <p>When a {@code protover} of {@code 2} or {@code 3} is supplied, the
 * connection switches dialect and the reply is rendered in the <em>new</em>
 * version (a RESP3 map, or a flattened array for RESP2). An unsupported version
 * yields {@code -NOPROTO}. The reply advertises server identity, the negotiated
 * protocol, the client id, mode, and role — the fields clients such as Lettuce
 * read to configure themselves.
 */
public final class HelloCommand implements Command {

    @Override
    public RespValue execute(CommandContext ctx) {
        ServerContext server = ctx.server();
        ClientConnection conn = ctx.connection();
        RespVersion negotiated = conn.protocol();

        int idx = 1;
        if (ctx.argCount() >= 2) {
            Integer ver = parseVersion(ctx.argText(1));
            if (ver == null) {
                return RespValue.error(
                        "NOPROTO unsupported protocol version");
            }
            negotiated = RespVersion.fromNumber(ver);
            idx = 2;

            // Optional AUTH / SETNAME modifiers.
            while (idx < ctx.argCount()) {
                String opt = ctx.argUpper(idx);
                switch (opt) {
                    case "AUTH" -> {
                        if (idx + 2 >= ctx.argCount()) {
                            return RespValue.error("ERR Syntax error in HELLO");
                        }
                        String user = ctx.argText(idx + 1);
                        String pass = ctx.argText(idx + 2);
                        if (!AuthCommand.authenticate(server, conn, user, pass)) {
                            return RespValue.error(
                                    "WRONGPASS invalid username-password pair or user is disabled.");
                        }
                        idx += 3;
                    }
                    case "SETNAME" -> {
                        if (idx + 1 >= ctx.argCount()) {
                            return RespValue.error("ERR Syntax error in HELLO");
                        }
                        conn.setName(ctx.argText(idx + 1));
                        idx += 2;
                    }
                    default -> {
                        return RespValue.error("ERR Syntax error in HELLO");
                    }
                }
            }
        }

        // If a password is set and the client still isn't authenticated, HELLO
        // must not leak server info — mirror Redis's guidance message.
        if (server.requiresAuth() && !conn.isAuthenticated()) {
            return RespValue.error(
                    "NOAUTH HELLO must be called with the client already authenticated, "
                            + "otherwise the HELLO <proto> AUTH <user> <pass> option can be used "
                            + "to authenticate the client and select the RESP protocol version at the same time");
        }

        conn.setProtocol(negotiated);
        return buildReply(server, conn);
    }

    private static Integer parseVersion(String text) {
        int v;
        try {
            v = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return null;
        }
        return (v == 2 || v == 3) ? v : null;
    }

    private static RespValue buildReply(ServerContext server, ClientConnection conn) {
        List<RespValue.MapEntry> entries = new ArrayList<>();
        entries.add(entry("server", RespValue.bulk("jediscore")));
        entries.add(entry("version", RespValue.bulk(server.config().version())));
        entries.add(entry("proto", RespValue.integer(conn.protocol().wireNumber())));
        entries.add(entry("id", RespValue.integer(conn.id())));
        entries.add(entry("mode", RespValue.bulk("standalone")));
        entries.add(entry("role", RespValue.bulk("master")));
        entries.add(entry("modules", new RespValue.Array(List.of())));
        return new RespValue.Map(entries);
    }

    private static RespValue.MapEntry entry(String key, RespValue value) {
        return new RespValue.MapEntry(RespValue.bulk(key), value);
    }
}
