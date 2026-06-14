package dev.jediscore.commands;

import dev.jediscore.engine.CommandContext;
import dev.jediscore.engine.CommandException;
import dev.jediscore.engine.CommandRegistry;
import dev.jediscore.engine.CommandSpec;
import dev.jediscore.engine.ServerContext;
import dev.jediscore.protocol.RespValue;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaString;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;

/**
 * Lua scripting: {@code EVAL}, {@code EVALSHA}, and {@code SCRIPT
 * LOAD}/{@code EXISTS}/{@code FLUSH}.
 *
 * <p>A script executes synchronously on the command thread, so {@code redis.call}
 * simply re-enters the dispatcher — there is no concurrency to guard. Writes use
 * <em>effects replication</em>: each inner command propagates to the AOF/WATCH
 * layer through the normal dispatch path, while {@code EVAL} itself is never
 * written to the AOF (replaying the script would be redundant and could behave
 * differently).
 *
 * <p>The Lua environment is sandboxed: {@code os}/{@code io} and the module
 * loaders are removed, and a metatable rejects creation of global variables, as
 * Redis does. Redis 7 {@code FUNCTION}s are deferred (see COMPATIBILITY.md).
 */
public final class ScriptingCommands {

    /** Commands a script may not call (Redis's {@code noscript} set, abridged). */
    private static final Set<String> NOSCRIPT = Set.of(
            "SUBSCRIBE", "UNSUBSCRIBE", "PSUBSCRIBE", "PUNSUBSCRIBE", "SSUBSCRIBE", "SUNSUBSCRIBE",
            "MULTI", "EXEC", "DISCARD", "WATCH", "UNWATCH",
            "EVAL", "EVALSHA", "SCRIPT", "WAIT");

    private final Map<String, byte[]> scripts = new HashMap<>();
    private final Map<String, LuaValue> compiledChunks = new HashMap<>();
    private final Globals globals;

    /** A script's execution context, set before each evaluation (command thread only). */
    private ServerContext server;
    private CommandContext invocation;

    private ScriptingCommands() {
        this.globals = JsePlatform.standardGlobals();
        sandbox(globals);
        globals.rawset("redis", buildRedisTable());
        installGlobalProtection(globals);
    }

    /**
     * Registers the scripting commands.
     *
     * @param registry the registry to populate
     */
    public static void registerAll(CommandRegistry registry) {
        ScriptingCommands instance = new ScriptingCommands();
        registry.register(CommandSpec.of("eval", -3, instance::eval));
        registry.register(CommandSpec.of("evalsha", -3, instance::evalsha));
        registry.register(CommandSpec.of("script", -2, instance::script));
    }

    // ---- commands ------------------------------------------------------------

    private RespValue eval(CommandContext ctx) {
        byte[] body = ctx.arg(1);
        String sha = sha1Hex(body);
        cache(sha, body);
        return run(ctx, sha, body);
    }

    private RespValue evalsha(CommandContext ctx) {
        String sha = ctx.argText(1).toLowerCase(Locale.ROOT);
        byte[] body = scripts.get(sha);
        if (body == null) {
            throw new CommandException("NOSCRIPT No matching script. Please use EVAL.");
        }
        return run(ctx, sha, body);
    }

    private RespValue script(CommandContext ctx) {
        String sub = ctx.argUpper(1);
        switch (sub) {
            case "LOAD" -> {
                if (ctx.argCount() != 3) {
                    throw new CommandException("ERR Unknown SCRIPT subcommand or wrong number of arguments");
                }
                byte[] body = ctx.arg(2);
                String sha = sha1Hex(body);
                compiledChunks.computeIfAbsent(sha, s -> compile(body)); // validate + warm
                cache(sha, body);
                return RespValue.bulk(sha);
            }
            case "EXISTS" -> {
                List<RespValue> out = new ArrayList<>();
                for (int i = 2; i < ctx.argCount(); i++) {
                    String sha = ctx.argText(i).toLowerCase(Locale.ROOT);
                    out.add(RespValue.integer(scripts.containsKey(sha) ? 1 : 0));
                }
                return new RespValue.Array(out);
            }
            case "FLUSH" -> {
                scripts.clear();
                compiledChunks.clear();
                return RespValue.OK;
            }
            default -> throw new CommandException(
                    "ERR Unknown SCRIPT subcommand or wrong number of arguments for '" + ctx.argText(1) + "'");
        }
    }

    // ---- evaluation ----------------------------------------------------------

    private RespValue run(CommandContext ctx, String sha, byte[] body) {
        long numKeys = Keyspaces.parseLong(ctx.arg(2));
        if (numKeys < 0) {
            throw new CommandException("ERR Number of keys can't be negative");
        }
        if (numKeys > ctx.argCount() - 3) {
            throw new CommandException("ERR Number of keys can't be greater than number of args");
        }
        LuaTable keys = new LuaTable();
        LuaTable argv = new LuaTable();
        for (int i = 0; i < numKeys; i++) {
            keys.set(i + 1, LuaString.valueOf(ctx.arg(3 + i)));
        }
        for (int i = 3 + (int) numKeys; i < ctx.argCount(); i++) {
            argv.set(i - 3 - (int) numKeys + 1, LuaString.valueOf(ctx.arg(i)));
        }
        globals.rawset("KEYS", keys);
        globals.rawset("ARGV", argv);

        LuaValue chunk = compiledChunks.computeIfAbsent(sha, s -> compile(body));
        this.server = ctx.server();
        this.invocation = ctx;
        try {
            LuaValue result = chunk.call();
            return luaToRedis(result);
        } catch (LuaError e) {
            return RespValue.error(scriptError(e));
        } finally {
            this.server = null;
            this.invocation = null;
        }
    }

    private LuaValue compile(byte[] body) {
        try {
            return globals.load(new String(body, StandardCharsets.UTF_8), "@user_script");
        } catch (LuaError e) {
            throw new CommandException("ERR Error compiling script (new function): " + e.getMessage());
        }
    }

    // ---- the redis.* library -------------------------------------------------

    private LuaTable buildRedisTable() {
        LuaTable redis = new LuaTable();
        redis.set("call", new RedisCall(true));
        redis.set("pcall", new RedisCall(false));
        redis.set("error_reply", new OneArgFunction() {
            @Override public LuaValue call(LuaValue msg) {
                LuaTable t = new LuaTable();
                t.set("err", msg.checkstring());
                return t;
            }
        });
        redis.set("status_reply", new OneArgFunction() {
            @Override public LuaValue call(LuaValue msg) {
                LuaTable t = new LuaTable();
                t.set("ok", msg.checkstring());
                return t;
            }
        });
        redis.set("sha1hex", new OneArgFunction() {
            @Override public LuaValue call(LuaValue arg) {
                return LuaValue.valueOf(sha1Hex(luaToBytes(arg)));
            }
        });
        redis.set("log", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                return LuaValue.NIL; // accepted no-op
            }
        });
        return redis;
    }

    /** Implements {@code redis.call} (raises on error) and {@code redis.pcall} (returns the error). */
    private final class RedisCall extends VarArgFunction {
        private final boolean raise;

        RedisCall(boolean raise) {
            this.raise = raise;
        }

        @Override
        public Varargs invoke(Varargs args) {
            byte[][] command = toCommand(args);
            if (command.length == 0) {
                throw new LuaError("Please specify at least one argument for this redis lib call");
            }
            String name = new String(command[0], StandardCharsets.UTF_8).toUpperCase(Locale.ROOT);
            if (NOSCRIPT.contains(name)) {
                throw new LuaError("This Redis command is not allowed from script");
            }
            RespValue reply = server.dispatcher().dispatch(
                    new CommandContext(server, invocation.connection(), command, false));
            if (reply instanceof RespValue.SimpleError err) {
                if (raise) {
                    throw new LuaError(err.message());
                }
                LuaTable t = new LuaTable();
                t.set("err", LuaValue.valueOf(err.message()));
                return t;
            }
            return redisToLua(reply);
        }
    }

    // ---- conversions ---------------------------------------------------------

    private byte[][] toCommand(Varargs args) {
        int n = args.narg();
        byte[][] out = new byte[n][];
        for (int i = 1; i <= n; i++) {
            LuaValue v = args.arg(i);
            if (v.type() == LuaValue.TSTRING || v.type() == LuaValue.TNUMBER) {
                out[i - 1] = luaToBytes(v);
            } else {
                throw new LuaError("Lua redis lib command arguments must be strings or integers");
            }
        }
        return out;
    }

    private static LuaValue redisToLua(RespValue value) {
        return switch (value) {
            case RespValue.Integer i -> LuaValue.valueOf(i.value());
            case RespValue.BulkString b -> LuaString.valueOf(b.data());
            case RespValue.SimpleString s -> {
                LuaTable t = new LuaTable();
                t.set("ok", LuaValue.valueOf(s.value()));
                yield t;
            }
            case RespValue.SimpleError e -> {
                LuaTable t = new LuaTable();
                t.set("err", LuaValue.valueOf(e.message()));
                yield t;
            }
            case RespValue.Null ignored -> LuaValue.FALSE;
            case RespValue.NullArray ignored -> LuaValue.FALSE;
            case RespValue.Array a -> {
                LuaTable t = new LuaTable();
                int idx = 1;
                for (RespValue item : a.items()) {
                    t.set(idx++, redisToLua(item));
                }
                yield t;
            }
            // RESP3-only shapes a script rarely sees: convert to the closest RESP2 form.
            case RespValue.Set s -> {
                LuaTable t = new LuaTable();
                int idx = 1;
                for (RespValue item : s.items()) {
                    t.set(idx++, redisToLua(item));
                }
                yield t;
            }
            case RespValue.Double d -> LuaValue.valueOf((long) d.value());
            case RespValue.Boolean b -> b.value() ? LuaValue.valueOf(1) : LuaValue.FALSE;
            default -> LuaValue.FALSE;
        };
    }

    private static RespValue luaToRedis(LuaValue value) {
        // Classify by exact Lua type: a numeric string ("123") reports isnumber()
        // true in LuaJ, so type() is the only safe discriminator.
        switch (value.type()) {
            case LuaValue.TNIL:
                return RespValue.NULL;
            case LuaValue.TBOOLEAN:
                return value.toboolean() ? RespValue.integer(1) : RespValue.NULL;
            case LuaValue.TNUMBER:
                return RespValue.integer((long) value.todouble()); // Lua numbers truncate to integers
            case LuaValue.TSTRING:
                return RespValue.bulk(luaToBytes(value));
            case LuaValue.TTABLE:
                LuaValue err = value.get("err");
                if (err.type() == LuaValue.TSTRING) {
                    return RespValue.error(err.checkjstring());
                }
                LuaValue ok = value.get("ok");
                if (ok.type() == LuaValue.TSTRING) {
                    return RespValue.simple(ok.checkjstring());
                }
                List<RespValue> items = new ArrayList<>();
                for (int i = 1; ; i++) {
                    LuaValue element = value.get(i);
                    if (element.isnil()) {
                        break; // a nil terminates the array, as in Redis
                    }
                    items.add(luaToRedis(element));
                }
                return new RespValue.Array(items);
            default:
                return RespValue.NULL; // functions, userdata, etc.
        }
    }

    private static byte[] luaToBytes(LuaValue value) {
        LuaString s = value.type() == LuaValue.TSTRING ? value.checkstring()
                : LuaValue.valueOf(value.tojstring()).checkstring();
        byte[] out = new byte[s.m_length];
        System.arraycopy(s.m_bytes, s.m_offset, out, 0, s.m_length);
        return out;
    }

    // ---- helpers -------------------------------------------------------------

    private void cache(String sha, byte[] body) {
        scripts.put(sha, body);
    }

    private static void sandbox(Globals globals) {
        for (String unsafe : new String[]{"os", "io", "package", "require", "loadfile", "dofile"}) {
            globals.rawset(unsafe, LuaValue.NIL);
        }
    }

    private static void installGlobalProtection(Globals globals) {
        LuaTable mt = new LuaTable();
        mt.set("__newindex", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue table, LuaValue key) {
                throw new LuaError("Script attempted to create global variable '" + key.tojstring() + "'");
            }
        });
        // __newindex needs three args (table, key, value); TwoArgFunction ignores the value,
        // which is fine since we always reject.
        globals.setmetatable(mt);
    }

    private static String scriptError(LuaError e) {
        String message = e.getMessage();
        if (message == null) {
            message = "script error";
        }
        // Strip LuaJ's "script:line:" prefix noise where present, then ensure an error code.
        int marker = message.indexOf("user_script:");
        if (marker >= 0) {
            int colon = message.indexOf(' ', marker);
            if (colon > 0) {
                message = message.substring(colon + 1).trim();
            }
        }
        return hasErrorCode(message) ? message : "ERR " + message;
    }

    private static boolean hasErrorCode(String message) {
        int space = message.indexOf(' ');
        if (space <= 0) {
            return false;
        }
        String first = message.substring(0, space);
        if (first.isEmpty()) {
            return false;
        }
        for (int i = 0; i < first.length(); i++) {
            if (!Character.isUpperCase(first.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static String sha1Hex(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xf, 16));
                sb.append(Character.forDigit(b & 0xf, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 not available", e);
        }
    }
}
