# Contributing to JediCore

JediCore is a wire-compatible Redis server in Java 21, built as serious
infrastructure. Contributions are welcome; this guide covers the layout, the
conventions, and the checks your change must pass.

## Prerequisites

- **JDK 21** (Temurin or any OpenJDK 21). The build pins the toolchain to 21.
- **Docker** (optional) — for the differential test and cross-compatibility checks
  against real Redis 7.4, and for the `docker compose` demo.

## Build &amp; test

```sh
./gradlew build                 # compile + all tests + checks
./gradlew :jediscore-server:test --tests "*PubSub*"   # a focused subset
./gradlew :jediscore-benchmarks:jmh -Pjmh.include=<Name>   # one JMH benchmark
./gradlew :jediscore-server:installDist                   # runnable image under build/install
```

Run a local instance:

```sh
java -cp 'jediscore-server/build/install/jediscore/lib/*' \
     dev.jediscore.server.JediCoreServer 0.0.0.0:6379
redis-cli -p 6379 ping
```

## Module layout

The module graph is acyclic; depend only "downward".

```
protocol + datastructures  →  engine  →  commands / network / persistence / replication  →  server
```

- **protocol** — RESP2/RESP3 codec (`RespValue`, `RespParser`, `RespEncoder`).
- **datastructures** — `Bytes`, the sealed `RedisValue` types and their encodings, `Dict`, `SkipList`, `Glob`.
- **engine** — keyspace (`Database`), the single-writer `CommandExecutor`, the
  dispatch SPI, and the command-thread-confined registries (pub/sub, WATCH,
  blocking, replication, ACL, stats, slowlog/latency).
- **commands** — command families, each in a registrar wired by `CoreCommands`.
- **network** — the Netty server and the RESP↔ByteBuf bridge.
- **persistence** — RDB + multi-part AOF.
- **replication** — the replica-side link.
- **server** — the composition root (`JediCore`) and `main`.

## Conventions

- **Threading is explicit.** All keyspace mutation happens on the single command
  thread; any new cross-cutting state goes in a command-thread-confined registry,
  with disconnect cleanup *submitted* to that thread (never touched from I/O
  threads). I/O threads only parse and write. See ARCHITECTURE.md.
- **No stubs or TODOs in merged code.** If something is partial, implement it
  honestly and record the gap in COMPATIBILITY.md.
- **Wire compatibility is the bar.** New commands must match Redis's replies; add
  an integration test (over a real socket) and, where it matters, extend the
  differential test against real Redis.
- **Hot paths are allocation-aware.** Add a JMH benchmark for anything on the
  per-command path and paste real numbers in the PR.
- **Javadoc** on public types and methods. Match the surrounding style.
- **Dependencies stay lean** — SLF4J/Logback, Micrometer, Netty, and LuaJ only.
  Anything else needs a strong, documented justification (e.g. BouncyCastle is
  test-scoped).

## A new command, end to end

1. Implement the handler in the relevant `*Commands` registrar (or a new one wired
   from `CoreCommands`).
2. Use `CommandException` for client-facing errors; `ctx.propagate(...)` to rewrite
   non-deterministic writes for the AOF/replicas.
3. Add an integration test in `jediscore-server/src/test`.
4. Update COMPATIBILITY.md (status + notes) and, if it touches a subsystem,
   ARCHITECTURE.md.

## CI

Every push/PR runs the GitHub Actions pipeline (`.github/workflows/ci.yml`):
**build + test**, a **JMH smoke run**, and a **redis-cli compatibility** job that
drives a live server with the official `redis-cli`. Keep it green.

## Commit messages

Imperative mood, a concise subject, and a body explaining the *why* and any honest
tradeoffs. One logical change per commit.
