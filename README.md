# JediCore

A production-grade, wire-compatible **Redis** server implemented from scratch in **Java 21**.

JediCore speaks the Redis Serialization Protocol (RESP2/RESP3) so that the official
`redis-cli` and standard Java clients (Jedis, Lettuce) connect and work unmodified.
It is built as a serious systems-programming exercise: clean module boundaries, an
explicit and defended concurrency model, allocation-aware hot paths, and a test +
benchmark discipline that runs in CI.

> **Status:** Phase 7 in progress — **7A** full `INFO` + live stats, **7B**
> diagnostics (`SLOWLOG`/`LATENCY`/`MONITOR`/`COMMAND GETKEYS`/`DEBUG`), **7C**
> config & lifecycle (`CONFIG`, redis.conf + CLI, graceful shutdown), **7D**
> security (ACL + AUTH, maxclients, protected-mode, rename-command). On top of
> **Phase 6** master/replica replication, both directions
> verified against real Redis 7.4: full **and partial** resync (`PSYNC`
> `FULLRESYNC`/`CONTINUE` with a backlog ring), deterministic command rewriting
> (`EXPIRE`→`PEXPIREAT`, `SPOP`→`SREM`, `SETEX`/`INCRBYFLOAT`→`SET …`), `WAIT`,
> `REPLICAOF`, read-only replicas, diskless `$EOF:` RDB, and `INFO`/`ROLE`. Manual
> failover via `REPLICAOF NO ONE` (Sentinel design documented). On top of Phase 5
> **advanced semantics** (Lua, blocking, transactions, Pub/Sub), **AOF + RDB
> persistence**, all five data types, `SCAN`, expiration, memory accounting, and
> eviction. Next: streams / cluster.
> See [`ARCHITECTURE.md`](ARCHITECTURE.md) and [`COMPATIBILITY.md`](COMPATIBILITY.md).

```text
$ redis-cli -p 6379 PING
PONG
$ redis-cli -p 6379 HELLO 3
1# "server" => "jediscore"
2# "version" => "7.4.0"
3# "proto" => (integer) 3
...
```

## Requirements

- **JDK 21** (the build pins a Java 21 toolchain; Gradle auto-provisions one via the
  Foojay resolver if your machine lacks it).
- No global Gradle install needed — use the bundled `./gradlew` wrapper.
- Docker (only for the wire-compatibility integration tests that arrive in Phase 2+).

## Quick start

```bash
# Build everything and run all unit tests
./gradlew build

# Run the (Phase 0) server: prints a banner and exits cleanly
./gradlew :jediscore-server:run

# Run the JMH benchmarks (fast smoke profile)
./gradlew :jediscore-benchmarks:jmh
```

A `Makefile` wraps the common commands (`make build`, `make test`, `make run`,
`make bench`, `make clean`) for convenience on Unix-like shells.

## Module layout

| Module | Responsibility |
|--------|----------------|
| `jediscore-protocol` | RESP2/RESP3 wire codec. Pure; no Netty, no keyspace. |
| `jediscore-datastructures` | Core data types (dict, list, set, zset, hash). Single-threaded semantics. |
| `jediscore-engine` | Keyspace, expiry, command-dispatch SPI, the single-writer execution loop. |
| `jediscore-commands` | Command implementations, grouped by family. |
| `jediscore-network` | Netty server + RESP↔ByteBuf adapter. |
| `jediscore-persistence` | Snapshot + append-only file (fork-free design). |
| `jediscore-replication` | Master/replica state machine (PSYNC). |
| `jediscore-server` | Runnable entry point; the only module that wires everything together. |
| `jediscore-benchmarks` | JMH micro-benchmarks for the hot paths. |

The dependency graph is acyclic; `protocol` and `datastructures` are dependency-free
leaves. See [`ARCHITECTURE.md`](ARCHITECTURE.md) for the diagram and the threading model.

## Tech stack

- **Java 21** — virtual threads, records, sealed types, pattern matching.
- **Gradle (Kotlin DSL)**, multi-module, with a version catalog and a convention plugin.
- **Netty** for the network layer.
- **SLF4J + Logback** for logging, **Micrometer** for metrics.
- **JUnit 5 + AssertJ** for tests, **jqwik** for property tests, **Testcontainers** for
  wire-compat integration tests, **JMH** for benchmarks.

## License

[MIT](LICENSE).
