package dev.jediscore.commands;

import dev.jediscore.engine.ClientConnection;
import dev.jediscore.engine.Command;
import dev.jediscore.engine.CommandContext;
import dev.jediscore.engine.ServerContext;
import dev.jediscore.protocol.RespValue;

/**
 * {@code AUTH password} or {@code AUTH username password}.
 *
 * <p>Phase-1 authentication is a single optional {@code requirepass} against the
 * implicit {@code default} user (Redis's pre-ACL behaviour). When no password is
 * configured, Redis — and JediCore — reject {@code AUTH} with a helpful error,
 * since there is nothing to authenticate against.
 */
public final class AuthCommand implements Command {

    @Override
    public RespValue execute(CommandContext ctx) {
        ServerContext server = ctx.server();
        String user;
        String password;
        if (ctx.argCount() == 2) {
            // AUTH <password> against the default user; an error if it is nopass.
            if (!server.requiresAuth()) {
                return RespValue.error(
                        "ERR Client sent AUTH, but no password is set. "
                                + "Did you mean AUTH <username> <password>?");
            }
            user = "default";
            password = ctx.argText(1);
        } else if (ctx.argCount() == 3) {
            user = ctx.argText(1);
            password = ctx.argText(2);
        } else {
            return RespValue.error("ERR wrong number of arguments for 'auth' command");
        }

        if (authenticate(server, ctx.connection(), user, password)) {
            return RespValue.OK;
        }
        return RespValue.error("WRONGPASS invalid username-password pair or user is disabled.");
    }

    /**
     * Validates credentials against the ACL and, on success, marks the connection
     * authenticated as that user. Shared with {@code HELLO}'s {@code AUTH} option.
     *
     * @param server     the server context
     * @param connection the connection to authenticate
     * @param user       the username
     * @param password   the supplied password
     * @return {@code true} if authentication succeeded
     */
    static boolean authenticate(ServerContext server, ClientConnection connection, String user, String password) {
        var authenticated = server.acl().authenticate(user, password);
        if (authenticated == null) {
            return false;
        }
        connection.setAuthenticated(true);
        connection.setUser(authenticated.name());
        return true;
    }
}
