package dev.jediscore.engine;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jediscore.protocol.RespValue;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CommandDispatcher}: arity enforcement, unknown-command
 * and wrong-arity error strings, the auth gate, and handler-exception handling.
 * Commands are registered inline so the test has no dependency on the commands
 * module.
 */
class CommandDispatcherTest {

    private CommandExecutor executor;
    private CommandRegistry registry;

    @BeforeEach
    void setUp() {
        executor = new CommandExecutor("test-cmd");
        registry = new CommandRegistry();
        registry.register(CommandSpec.of("ping", -1, ctx -> RespValue.PONG));
        registry.register(CommandSpec.of("get", 2, ctx -> RespValue.bulk("value")));
        registry.register(CommandSpec.of("boom", 1, ctx -> {
            throw new IllegalStateException("kaboom");
        }));
    }

    @AfterEach
    void tearDown() {
        executor.close();
    }

    private static byte[][] args(String... parts) {
        byte[][] out = new byte[parts.length][];
        for (int i = 0; i < parts.length; i++) {
            out[i] = parts[i].getBytes(StandardCharsets.UTF_8);
        }
        return out;
    }

    private static ServerContext serverContext(ServerConfig config, CommandRegistry reg, CommandExecutor exec) {
        return new ServerContext(config, reg, exec);
    }

    private RespValue dispatch(ServerConfig config, ClientConnection conn, String... parts) {
        ServerContext ctx = serverContext(config, registry, executor);
        return new CommandDispatcher(ctx).dispatch(new CommandContext(ctx, conn, args(parts)));
    }

    private static ClientConnection authedConn() {
        return new ClientConnection(1, "127.0.0.1:1", "127.0.0.1:6379", true);
    }

    @Test
    void dispatchesKnownCommand() {
        RespValue reply = dispatch(ServerConfig.defaults("127.0.0.1", 0), authedConn(), "PING");
        assertThat(reply).isEqualTo(RespValue.PONG);
    }

    @Test
    void caseInsensitiveLookup() {
        RespValue reply = dispatch(ServerConfig.defaults("127.0.0.1", 0), authedConn(), "pInG");
        assertThat(reply).isEqualTo(RespValue.PONG);
    }

    @Test
    void unknownCommandHasRedisCompatibleError() {
        RespValue reply = dispatch(ServerConfig.defaults("127.0.0.1", 0), authedConn(), "FOObar", "a", "b");
        assertThat(reply).isInstanceOf(RespValue.SimpleError.class);
        assertThat(((RespValue.SimpleError) reply).message())
                .isEqualTo("ERR unknown command 'FOObar', with args beginning with: 'a', 'b', ");
    }

    @Test
    void wrongArityHasRedisCompatibleError() {
        RespValue reply = dispatch(ServerConfig.defaults("127.0.0.1", 0), authedConn(), "GET"); // needs 2
        assertThat(((RespValue.SimpleError) reply).message())
                .isEqualTo("ERR wrong number of arguments for 'get' command");
    }

    @Test
    void handlerExceptionBecomesInternalError() {
        RespValue reply = dispatch(ServerConfig.defaults("127.0.0.1", 0), authedConn(), "BOOM");
        assertThat(((RespValue.SimpleError) reply).message()).isEqualTo("ERR internal error");
    }

    @Test
    void authGateBlocksWhenPasswordSetAndUnauthenticated() {
        ServerConfig secured = new ServerConfig(
                "127.0.0.1", 0, 511, Optional.of("secret"), ServerConfig.DEFAULT_VERSION, "runid",
                ServerConfig.DEFAULT_DATABASES,
                ServerConfig.DEFAULT_HASH_MAX_LISTPACK_ENTRIES,
                ServerConfig.DEFAULT_HASH_MAX_LISTPACK_VALUE);
        ClientConnection unauth = new ClientConnection(2, "127.0.0.1:2", "127.0.0.1:6379", false);
        RespValue reply = dispatch(secured, unauth, "GET", "k");
        assertThat(((RespValue.SimpleError) reply).message()).isEqualTo("NOAUTH Authentication required.");
    }

    @Test
    void authGateAllowsAfterAuthentication() {
        ServerConfig secured = new ServerConfig(
                "127.0.0.1", 0, 511, Optional.of("secret"), ServerConfig.DEFAULT_VERSION, "runid",
                ServerConfig.DEFAULT_DATABASES,
                ServerConfig.DEFAULT_HASH_MAX_LISTPACK_ENTRIES,
                ServerConfig.DEFAULT_HASH_MAX_LISTPACK_VALUE);
        ClientConnection conn = new ClientConnection(3, "127.0.0.1:3", "127.0.0.1:6379", false);
        conn.setAuthenticated(true);
        RespValue reply = dispatch(secured, conn, "GET", "k");
        assertThat(reply).isEqualTo(RespValue.bulk("value"));
    }

    @Test
    void emptyRequestReturnsNoReply() {
        ServerConfig config = ServerConfig.defaults("127.0.0.1", 0);
        ServerContext ctx = serverContext(config, registry, executor);
        RespValue reply = new CommandDispatcher(ctx).dispatch(new CommandContext(ctx, authedConn(), new byte[0][]));
        assertThat(reply).isNull();
    }
}
