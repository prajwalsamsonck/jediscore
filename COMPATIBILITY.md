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

<!--
  Maintenance: as each command lands, add a row above with its status and any
  behavioural notes (edge cases, RESP3 differences, deviations from Redis). Group
  rows by family once there are enough to warrant subheadings (Strings, Lists, …).
-->
