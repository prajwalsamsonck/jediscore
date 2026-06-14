# Redis Compatibility Matrix

This document tracks JediCore's command-level compatibility with Redis. It is a living
document, updated every phase as commands are implemented.

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
| `AUTH` | 🚧 Partial | Single `requirepass` against the implicit `default` user; full ACLs deferred. |
| `QUIT` | ✅ Done | Replies `+OK`, then closes after flush. |
| `RESET` | ✅ Done | Resets protocol/name/auth and drops all pub/sub subscriptions; transaction state reset when that lands. |
| `CLIENT ID` | ✅ Done | |
| `CLIENT GETNAME` / `SETNAME` | ✅ Done | `SETNAME` rejects spaces/newlines/control chars. |
| `CLIENT INFO` | 🚧 Partial | Representative field subset (no buffer/memory counters yet). |
| `CLIENT SETINFO` | ✅ Done | Accepts `lib-name`/`lib-ver` advertisements. |
| `COMMAND` | 🚧 Partial | Full table, `COUNT`, `LIST`, `INFO`; `DOCS` returns an empty (valid) map; key specs reported as 0. |

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

<!--
  Maintenance: as each command lands, add a row above with its status and any
  behavioural notes (edge cases, RESP3 differences, deviations from Redis). Group
  rows by family once there are enough to warrant subheadings (Strings, Lists, …).
-->
