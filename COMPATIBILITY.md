# Redis Compatibility Matrix

This document tracks JediCore's command-level compatibility with Redis. It is a living
document, updated every phase as commands are implemented.

## Coverage at a glance

JediCore targets **wire compatibility with Redis 7.4** (RESP2 and RESP3) and is
verified continuously by a property-based **differential test** against a real
Redis and a **redis-cli compatibility suite** in CI.

- **Data types** — Strings, Hashes, Lists, Sets, Sorted Sets: complete, with
  Redis's listpack/intset/quicklist/skiplist encoding transitions.
- **Keyspace** — generic key commands, the full `SCAN` family, two-tier expiration,
  `maxmemory` + all 8 eviction policies, `SWAPDB`/`MOVE`/`COPY`.
- **Persistence** — RDB (cross-compatible both ways) and multi-part AOF with all
  three `appendfsync` policies and `BGREWRITEAOF`.
- **Advanced semantics** — Pub/Sub (+ sharded + RESP3 push), transactions
  (MULTI/EXEC/WATCH), blocking commands, and Lua scripting (EVAL/EVALSHA/SCRIPT).
- **Replication** — master and replica, full + partial resync, verified both ways
  against real Redis.
- **Operations** — full `INFO`, `CONFIG`, `SLOWLOG`/`LATENCY`/`MONITOR`,
  `COMMAND`/`DEBUG`, ACL + AUTH, TLS, Prometheus metrics, graceful shutdown.

**Not implemented (honestly):** Streams (`X*`), Cluster (hash slots / `MOVED`/`ASK`),
Bitmaps/HyperLogLog/Geo, Redis Functions (`FUNCTION`/`FCALL`), Sentinel automatic
failover (a design is documented; manual failover via `REPLICAOF NO ONE` works), and
ACL key/channel-pattern *enforcement* (patterns are parsed and reported only).

## Legend

| Status | Meaning |
|--------|---------|
| ✅ Done | Implemented and covered by tests, behaviour matches Redis. |
| 🚧 Partial | Implemented, but with documented gaps (see Notes). |
| 📋 Planned | Scheduled for a specific phase. |
| ❌ N/A | Not planned (e.g. cluster-only or deprecated). |

## Protocol support

| Feature | Status | Notes |
|---------|--------|-------|
| Cursor iteration (`SCAN` family) | ✅ Done | Custom `Dict` with Redis's reverse-binary bucket cursor; full iteration is complete under modification/resize. |
| RESP2 | ✅ Done | Default dialect; full encode/decode. |
| RESP3 | ✅ Done | Negotiated via `HELLO 3`; all typed replies supported. |
| Inline commands | ✅ Done | Whitespace split with single/double-quote and escape handling. |
| Pipelining | ✅ Done | Multiple commands per read; partials buffered across reads. |
| Protocol errors | ✅ Done | `-ERR Protocol error: …` then connection close, as in Redis. |

## Command matrix

### Connection & server

| Command | Status | Notes |
|---------|--------|-------|
| `PING` | ✅ Done | `+PONG`, or echoes one argument; arity-checks >2 args. |
| `ECHO` | ✅ Done | |
| `HELLO` | ✅ Done | Protocol negotiation (2/3), `AUTH`/`SETNAME` options, `NOPROTO` on bad version. |
| `AUTH` | ✅ Done | `AUTH password` (default user) and `AUTH username password`; validates against the ACL (SHA-256 password hashes). |
| `ACL` | 🚧 Partial | `WHOAMI`/`LIST`/`USERS`/`CAT`/`GETUSER`/`SETUSER`/`DELUSER`/`GENPASS`. Command rules and the `@read`/`@write`/`@admin`/`@all` categories are **enforced**; key/channel patterns are parsed, stored, and reported but **not yet enforced**; ACL selectors are not supported. |
| `maxclients` | ✅ Done | New connections beyond the limit are rejected at accept with `-ERR max number of clients reached`; settable via `CONFIG`. |
| `protected-mode` | ✅ Done | On by default: a non-loopback client cannot run commands when no password is set (`DENIED …`). Internal replay/master-link connections are exempt. |
| `rename-command` | ✅ Done | Rename or disable (rename to `""`) commands via the config file / CLI. |
| Input size limits | ✅ Done | Multibulk capped at 1M elements; bulk strings at 512 MB (`proto-max-bulk-len`), enforced in the parser. |
| TLS | ✅ Done | `tls yes` fronts the listening port with a Netty SSL handler from `tls-cert-file`/`tls-key-file` (PEM), or a self-signed cert for dev (needs BouncyCastle on the classpath; not bundled). Verified with a real TLS handshake + PING. Single-port (not a separate `tls-port`). |
| Metrics (Prometheus) | ✅ Done | `metrics-port N` starts an HTTP `/metrics` endpoint with Micrometer: `jedicore_*` counters/gauges (commands, connections, keyspace hits/misses, expired/evicted, clients, memory, keys, replicas) plus JVM memory/CPU binders. |
| Structured logging | 🚧 Partial | Logback with an ISO-8601, level/thread/logger/MDC pattern; JSON output is a documented opt-in (Logstash encoder), not bundled. |
| `QUIT` | ✅ Done | Replies `+OK`, then closes after flush. |
| `RESET` | ✅ Done | Resets protocol/name/auth and drops all pub/sub subscriptions; transaction state reset when that lands. |
| `CLIENT ID` | ✅ Done | |
| `CLIENT GETNAME` / `SETNAME` | ✅ Done | `SETNAME` rejects spaces/newlines/control chars. |
| `CLIENT INFO` | 🚧 Partial | Representative field subset (no buffer/memory counters yet). |
| `CLIENT SETINFO` | ✅ Done | Accepts `lib-name`/`lib-ver` advertisements. |
| `COMMAND` | ✅ Done | `COUNT`/`LIST`/`INFO`/`DOCS`/`GETKEYS`. Key specs from a built-in table (default `1,1,1`; no-key and multi-key overrides; `EVAL`/`EVALSHA` via `numkeys`) — best-effort, not Redis's full flag-rich spec. `DOCS` returns a minimal map. |
| `INFO` | ✅ Done | All sections (Server/Clients/Memory/Persistence/Stats/Replication/CPU/Cluster/Keyspace) with live counters from `ServerStats`; section filter supported. `total_net_*_bytes` are 0 (not yet tracked); `used_memory_rss` is the JVM heap-in-use proxy; CPU is total process time attributed to user. |
| `SLOWLOG` | ✅ Done | `GET`/`LEN`/`RESET`/`HELP`. Bounded newest-first ring; per-command timing from the dispatcher; `slowlog-log-slower-than` (default 10ms) and `slowlog-max-len` (128) configurable. Args truncated to 32×128B like Redis. |
| `LATENCY` | 🚧 Partial | `LATEST`/`HISTORY`/`RESET`/`DOCTOR` for the `command` event; off by default (`latency-monitor-threshold` 0). Other built-in event types (fork, expire-cycle, …) not tracked. |
| `MONITOR` | ✅ Done | Streams every executed command in Redis's `<ts> [db addr] "CMD" "arg"…` format; `AUTH`/`HELLO`/`RESET` and the replication link are not echoed. |
| `DEBUG` | 🚧 Partial | `RELOAD`, `SLEEP`, `OBJECT` (encoding/serializedlength), `SET-ACTIVE-EXPIRE` (real toggle), `JMAP`/`QUICKLIST-PACKED-THRESHOLD`/`STRINGMATCH-LEN` no-ops. |
| `CONFIG GET`/`SET` | 🚧 Partial | A curated parameter set: maxmemory + policy + samples, requirepass, the listpack/intset encoding thresholds, `slowlog-*`, `latency-monitor-threshold` (settable); port/bind/databases/maxclients/appendonly/dir (read-only). `GET` honours globs and returns a map. Other params are unknown to `CONFIG`. |
| `CONFIG RESETSTAT` | ✅ Done | Resets the `INFO stats` counters. |
| `CONFIG REWRITE` | ✅ Done | Rewrites the loaded config file with current settable values; errors if started without a file. |
| redis.conf + CLI | ✅ Done | `jediscore [config.conf] [host:port] [--opt value …]`; CLI overrides file directives. Curated directive set (port/bind/databases/maxmemory*/requirepass/encoding thresholds/dir/save/appendonly/appendfsync). |
| `SHUTDOWN [NOSAVE\|SAVE]` | ✅ Done | Persists per the save-on-shutdown policy, then exits the standalone server; the SIGTERM hook does a final RDB save when save points are configured. |

### Generic key commands

| Command | Status | Notes |
|---------|--------|-------|
| `DEL` / `UNLINK` | ✅ Done | UNLINK is synchronous (semantically identical reply). |
| `EXISTS` | ✅ Done | Counts multiplicity. |
| `TYPE` | ✅ Done | |
| `KEYS` | ✅ Done | Redis glob (`*`, `?`, `[...]`, `\`). |
| `RENAME` / `RENAMENX` | ✅ Done | Moves value and TTL; `ERR no such key` when source absent. |
| `RANDOMKEY` | ✅ Done | |
| `TOUCH` | ✅ Done | |
| `COPY` | ✅ Done | `REPLACE` and `DB` options; deep copy + TTL. |
| `SELECT` | ✅ Done | 16 databases; `ERR DB index is out of range`. |
| `DBSIZE` | ✅ Done | Excludes logically-expired keys. |
| `FLUSHDB` / `FLUSHALL` | ✅ Done | `ASYNC`/`SYNC` accepted (flushing is synchronous). |
| `OBJECT ENCODING/REFCOUNT/IDLETIME/FREQ` | 🚧 Partial | REFCOUNT always 1 (no object sharing); FREQ requires an LFU policy, IDLETIME a non-LFU policy. |
| `MEMORY USAGE` / `MEMORY DOCTOR` | ✅ Done | Estimated bytes (not allocator-exact); DOCTOR gives a basic summary. |
| `EXPIRE`/`TTL`/`PERSIST` family | ✅ Done | NX/XX/GT/LT; EXPIRETIME/PEXPIRETIME. Lazy + active (background) expiration. |
| `maxmemory` eviction | ✅ Done | All 8 policies (noeviction, {allkeys,volatile}-{lru,lfu,random}, volatile-ttl); sampled LRU/clock-based LFU. |
| `SCAN` | ✅ Done | `MATCH`/`COUNT`/`TYPE`; reverse-binary bucket cursor. |
| `SWAPDB` | ✅ Done | |

### Persistence

| Command / feature | Status | Notes |
|---------|--------|-------|
| `SAVE` | ✅ Done | Synchronous RDB write (blocks the command thread). |
| `BGSAVE` | ✅ Done | Fork-free: deep-copy snapshot on the command thread, serialize off-thread. |
| `LASTSAVE` | ✅ Done | |
| `DEBUG RELOAD` | ✅ Done | Save + flush + reload, round-tripping through RDB. |
| RDB file format | ✅ Done | Cross-compatible with `redis-server` both ways: writes plain encodings (CRC-64 verified by Redis); reads Redis 7.x encodings (intset, listpack, quicklist v2, int-encoded + LZF strings). |
| RDB save points | ✅ Done | `save 900 1 …`; auto-BGSAVE on the cron. |
| Startup load | ✅ Done | Loads `dump.rdb` from `dir` if present; AOF takes precedence when `appendonly yes`. |
| AOF (`appendonly`, multi-part) | ✅ Done | Redis 7+ multi-part layout: `appendonlydir/` with a `.base.rdb`, `.incr.aof`, and `.manifest`. Verbatim command propagation (with `SELECT` on DB change). |
| `appendfsync` | ✅ Done | `always` (fsync per command), `everysec` (fsync on the cron), `no` (OS-flushed). Crash-consistency verified under `always` via hard `Stop-Process`. |
| `BGREWRITEAOF` | ✅ Done | Fork-free: deep-copy snapshot + atomic incr-file switch on the command thread, base RDB + manifest committed off-thread, old files deleted. |
| AOF limitations | 🚧 Partial | Commands are propagated verbatim — no `SPOP`→`SREM` or `EXPIRE`→`PEXPIREAT` rewriting. Documented crash window between the incr switch and the manifest commit during a rewrite. |

### Strings

| Command | Status | Notes |
|---------|--------|-------|
| `SET` | ✅ Done | `EX`/`PX`/`EXAT`/`PXAT`/`NX`/`XX`/`KEEPTTL`/`GET`. |
| `GET` `GETSET` `GETDEL` `GETEX` | ✅ Done | |
| `APPEND` `STRLEN` | ✅ Done | Mutation forces `raw` encoding. |
| `INCR` `DECR` `INCRBY` `DECRBY` | ✅ Done | Overflow → `ERR ... would overflow`. |
| `INCRBYFLOAT` | 🚧 Partial | IEEE-754 double, not C long double — least-significant digits may differ for non-exact decimals (documented in code). |
| `SETRANGE` `GETRANGE` | ✅ Done | |
| `MSET` `MGET` `MSETNX` `SETNX` `SETEX` `PSETEX` | ✅ Done | |

### Hashes

| Command | Status | Notes |
|---------|--------|-------|
| `HSET` `HSETNX` `HMSET` `HGET` `HMGET` `HGETALL` | ✅ Done | listpack↔hashtable encoding. |
| `HDEL` `HEXISTS` `HKEYS` `HVALS` `HLEN` `HSTRLEN` | ✅ Done | Empty hash is deleted. |
| `HINCRBY` | ✅ Done | Validates before creating the key. |
| `HINCRBYFLOAT` | 🚧 Partial | Same double caveat as `INCRBYFLOAT`. |
| `HRANDFIELD` | 🚧 Partial | `WITHVALUES` returns a flat array (RESP3 nesting deferred). |
| `HSCAN` | ✅ Done | `MATCH`/`COUNT`/`NOVALUES`. |

### Lists

| Command | Status | Notes |
|---------|--------|-------|
| `LPUSH` `RPUSH` `LPUSHX` `RPUSHX` | ✅ Done | listpack↔quicklist encoding. |
| `LPOP` `RPOP` | ✅ Done | Optional count. |
| `LRANGE` `LLEN` `LINDEX` | ✅ Done | Negative indices. |
| `LSET` `LINSERT` `LREM` `LTRIM` | ✅ Done | Argument validation ordered as in Redis. |
| `RPOPLPUSH` `LMOVE` | ✅ Done | Same-key rotation supported. |
| `LPOS` | ✅ Done | `RANK`/`COUNT`/`MAXLEN`. |

### Sets

| Command | Status | Notes |
|---------|--------|-------|
| `SADD` `SREM` `SCARD` `SISMEMBER` `SMISMEMBER` | ✅ Done | intset↔listpack↔hashtable encoding. |
| `SMEMBERS` | ✅ Done | Order unspecified (as in Redis). |
| `SPOP` `SRANDMEMBER` | ✅ Done | Optional count (negative = with repetition). |
| `SUNION` `SINTER` `SDIFF` (+ `STORE`) | ✅ Done | |
| `SINTERCARD` | ✅ Done | `LIMIT` option. |
| `SMOVE` | ✅ Done | Redis argument/lookup ordering. |
| `SSCAN` | ✅ Done | `MATCH`/`COUNT`. |

### Sorted sets

| Command | Status | Notes |
|---------|--------|-------|
| `ZADD` | ✅ Done | `NX`/`XX`/`GT`/`LT`/`CH`/`INCR`; listpack↔skiplist encoding. |
| `ZINCRBY` `ZREM` `ZSCORE` `ZMSCORE` `ZCARD` | ✅ Done | |
| `ZRANK` `ZREVRANK` | ✅ Done | `WITHSCORE` option. |
| `ZRANGE` | ✅ Done | `BYSCORE`/`BYLEX`/`REV`/`LIMIT`/`WITHSCORES`. |
| `ZREVRANGE` `ZRANGEBYSCORE` `ZREVRANGEBYSCORE` `ZRANGEBYLEX` `ZREVRANGEBYLEX` | ✅ Done | |
| `ZCOUNT` `ZLEXCOUNT` | ✅ Done | Inclusive/exclusive bounds, `-inf`/`+inf`/`-`/`+`. |
| `ZPOPMIN` `ZPOPMAX` | ✅ Done | Optional count. |
| `ZRANGESTORE` | ✅ Done | |
| `ZUNION` `ZINTER` `ZDIFF` (+ `STORE`) | ✅ Done | `WEIGHTS`/`AGGREGATE`; accepts sets as inputs (score 1). |
| `ZINTERCARD` | ✅ Done | `LIMIT`. |
| `ZSCAN` | ✅ Done | `MATCH`/`COUNT`; returns member/score pairs. |

### Expiration

| Command | Status | Notes |
|---------|--------|-------|
| `EXPIRE` `PEXPIRE` `EXPIREAT` `PEXPIREAT` | ✅ Done | `NX`/`XX`/`GT`/`LT` options; past times delete immediately. |
| `TTL` `PTTL` `EXPIRETIME` `PEXPIRETIME` | ✅ Done | |
| `PERSIST` | ✅ Done | |
| `SWAPDB` | ✅ Done | |

### Pub/Sub

| Command | Status | Notes |
|---------|--------|-------|
| `SUBSCRIBE` `UNSUBSCRIBE` | ✅ Done | One confirmation frame per channel; running subscription count. Unsubscribe with no args drops all. |
| `PSUBSCRIBE` `PUNSUBSCRIBE` | ✅ Done | Glob pattern matching via the shared `Glob` matcher; `pmessage` delivery. |
| `PUBLISH` | ✅ Done | Returns the receiver count (direct channel + matching pattern subscribers). |
| `SSUBSCRIBE` `SUNSUBSCRIBE` `SPUBLISH` | ✅ Done | Redis 7 sharded pub/sub kept in a separate index; a regular `PUBLISH` never reaches shard subscribers and vice-versa. Single-node, so every shard channel lives here. |
| `PUBSUB CHANNELS`/`NUMSUB`/`NUMPAT`/`SHARDCHANNELS`/`SHARDNUMSUB` | ✅ Done | |
| RESP3 push type | ✅ Done | Messages and confirmations are `>` pushes in RESP3, plain arrays in RESP2 (encoder downgrade). |
| Subscriber-mode restriction | ✅ Done | RESP2 subscribers may only run `(P\|S)SUBSCRIBE`/`(P\|S)UNSUBSCRIBE`/`PING`/`QUIT`/`RESET`; `PING` replies in the `["pong", msg]` array form. RESP3 lifts the restriction. |

### Transactions

| Command | Status | Notes |
|---------|--------|-------|
| `MULTI` | ✅ Done | Nested `MULTI` → error; subsequent non-control commands reply `+QUEUED`. |
| `EXEC` | ✅ Done | Runs queued commands in order, replies with the results array. CAS failure → nil array (`*-1`). `EXEC` without `MULTI` → error. |
| `DISCARD` | ✅ Done | Drops the queue and unwatches; without `MULTI` → error. |
| `WATCH` | ✅ Done | Optimistic lock; `WATCH` inside `MULTI` → error. |
| `UNWATCH` | ✅ Done | Clears all watches and the CAS-dirty flag. |
| Queue-time errors | ✅ Done | Unknown command / bad arity poisons the transaction → `EXEC` replies `-EXECABORT`. |
| Runtime errors | ✅ Done | A queued command that errors at run time (e.g. `WRONGTYPE`) does not abort; its error is an element of the results array. |
| CAS invalidation | ✅ Done | A watched key created/overwritten/deleted/expired (lazy or active), TTL-changed, in-place-mutated, flushed, or swapped invalidates the transaction. Conservative: a watched key whose bytes equal a non-key argument is also touched (aborts more eagerly than Redis, never misses a real change). |
| AOF propagation | 🚧 Partial | An executed transaction's effective writes are propagated to the AOF individually, not wrapped in `MULTI`/`EXEC`. Replay is equivalent on the single command thread. |

### Blocking commands

| Command | Status | Notes |
|---------|--------|-------|
| `BLPOP` `BRPOP` | ✅ Done | Multi-key, FIFO wakeup, fractional-second timeout (`0` = forever); nil array on timeout. |
| `BLMOVE` `BRPOPLPUSH` | ✅ Done | Blocks on the source; a push into the destination chains to wake a client blocked there. Nil bulk on timeout. |
| `BLMPOP` | ✅ Done | `numkeys`, `LEFT`/`RIGHT`, optional `COUNT`; replies `[key, [elements]]`. |
| `BZPOPMIN` `BZPOPMAX` | ✅ Done | Multi-key; replies `[key, member, score]`. |
| `WAIT` | 🚧 Partial | No replication yet, so it reports 0 acked replicas — returns `0` immediately for `numreplicas 0`, otherwise blocks until the (millisecond) timeout and returns `0`. |
| Blocking inside `MULTI`/`EXEC` | ✅ Done | Never parks: returns the timeout reply immediately if not satisfiable, matching Redis. |
| Timeout accuracy | ✅ Done | A daemon scheduler fires at the deadline and hands the timeout to the command thread (no busy-waiting). |
| Connection suspension | 🚧 Partial | A blocked connection's *subsequent* commands are still processed (we don't pause its read side); well-behaved clients that await the reply are unaffected. |
| AOF propagation | ✅ Done | A served blocking pop propagates the effective `LPOP`/`RPOP`/`LMOVE`/`ZPOPMIN`… — never the blocking command itself. |

### Scripting (Lua)

| Command | Status | Notes |
|---------|--------|-------|
| `EVAL` `EVALSHA` | ✅ Done | LuaJ 5.1; `KEYS`/`ARGV` bound; numkeys validated. Scripts run on the command thread; compiled chunks are cached by SHA-1. |
| `SCRIPT LOAD`/`EXISTS`/`FLUSH` | ✅ Done | LOAD validates + returns the SHA-1; EXISTS returns 0/1 per digest. |
| `redis.call` / `redis.pcall` | ✅ Done | call raises (aborting the script) on a Redis error; pcall returns the `{err=…}` table. NOSCRIPT commands (SUBSCRIBE family, MULTI/EXEC/WATCH, EVAL/SCRIPT, WAIT) rejected; blocking commands run non-blocking. |
| `redis.error_reply` / `status_reply` / `sha1hex` / `log` | ✅ Done | |
| Value conversions | ✅ Done | Redis↔Lua both ways (int↔number, bulk↔string, status↔`{ok}`, error↔`{err}`, nil↔false, array↔table with nil-termination). Binary-safe. |
| Sandbox | ✅ Done | `os`/`io`/module loaders removed; creating a global variable errors, as in Redis. |
| Replication of effects | 🚧 Partial | A script's inner writes propagate to the AOF individually (effects replication), not wrapped in `MULTI`/`EXEC`; `EVAL` itself is not written to the AOF. |
| `FUNCTION` (Redis 7 functions) | 📋 Deferred | Not implemented; the spec marks it optional. |
| Lua version | 🚧 Partial | LuaJ implements Lua 5.1 (as Redis does); `cjson`/`cmsgpack`/`struct`/`bit` helper libraries are not bundled. |

### Replication (Phases 6A master, 6B replica)

| Command / feature | Status | Notes |
|---------|--------|-------|
| `PSYNC` (full resync) | ✅ Done | Both directions verified against real Redis 7.4: a real `redis-server` replicates from us, and we replicate from a real master (incl. **diskless `$EOF:`** RDB transfer). |
| `PSYNC` (partial resync) | ✅ Done | A reconnecting replica with a matching replid whose offset is still in the backlog gets `+CONTINUE` and the missed bytes; otherwise it falls back to a full resync. Both the master serving and the replica requesting it are implemented (offsets follow Redis's 1-based next-byte convention). |
| Legacy `SYNC` | ✅ Done | Sends the RDB without the `FULLRESYNC` line. |
| `REPLCONF` | ✅ Done | `listening-port`, `capa`, `ACK <offset>`, `GETACK`; lenient on unknown options. |
| `REPLICAOF`/`SLAVEOF` | ✅ Done | `host port` connects out, syncs (full or partial), and applies the stream; `NO ONE` promotes back to master (dataset retained). |
| Replica read-only mode | ✅ Done | Client writes rejected with `READONLY`; the master-link applying the stream is exempt. Reads served normally. |
| Command propagation | ✅ Done | Shared stream with `SELECT` on db change; offset + backlog advance once a replica has attached. |
| Deterministic rewriting | ✅ Done | `EXPIRE`-family→`PEXPIREAT`, `SPOP`→`SREM`/`DEL`, `SET … EX/PX`→`SET … PXAT`, `SETEX`/`PSETEX`→`SET … PXAT`, `GETEX`→`PEXPIREAT`/`PERSIST`, `INCRBYFLOAT`→`SET`, `HINCRBYFLOAT`→`HSET`. The same rewrites feed the AOF (so AOF replay is deterministic too). |
| `SENTINEL` / `FAILOVER` | 📋 Design only | Not implemented; the failover design is documented in ARCHITECTURE.md. Manual failover: `REPLICAOF NO ONE` on the promoted replica, `REPLICAOF <new master>` on the rest. |
| `WAIT` | ✅ Done | Counts replicas acked at the target offset; sends `GETACK` and blocks on the wait-queue until enough ack or timeout. |
| `INFO replication` | ✅ Done | Master: `role:master`, `connected_slaves`, `slaveN:…`. Replica: `role:slave`, `master_host`/`port`, `master_link_status`, `slave_repl_offset`. |
| `ROLE` | ✅ Done | Master `["master", offset, [[ip,port,ack],…]]`; replica `["slave", host, port, state, offset]`. |
| Replica keepalives | ✅ Done | Bare-`\n` keepalives in the stream are skipped and counted toward the offset, matching Redis. |

<!--
  Maintenance: as each command lands, add a row above with its status and any
  behavioural notes (edge cases, RESP3 differences, deviations from Redis). Group
  rows by family once there are enough to warrant subheadings (Strings, Lists, …).
-->
