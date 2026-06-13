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
| `RESET` | ✅ Done | Resets protocol/name/auth; transactions & pubsub state reset when those land. |
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
| `OBJECT ENCODING/REFCOUNT/IDLETIME` | 🚧 Partial | REFCOUNT always 1 (no object sharing). |
| `EXPIRE`/`TTL`/`PERSIST` family | ✅ Done | NX/XX/GT/LT; EXPIRETIME/PEXPIRETIME. |
| `SCAN` | ✅ Done | `MATCH`/`COUNT`/`TYPE`; reverse-binary bucket cursor. |
| `SWAPDB` | ✅ Done | |

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

<!--
  Maintenance: as each command lands, add a row above with its status and any
  behavioural notes (edge cases, RESP3 differences, deviations from Redis). Group
  rows by family once there are enough to warrant subheadings (Strings, Lists, …).
-->
