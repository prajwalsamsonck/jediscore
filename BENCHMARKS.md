# JediCore Benchmarks

Two kinds of numbers: **JMH microbenchmarks** that isolate engine hot paths (no
network), and a **`redis-benchmark` run** against the running server compared
against real Redis 7.4 on the same machine. Everything here is measured, not
estimated; where the harness limits what a number means, that is called out.

> Environment: Windows 11, JDK 21 (Temurin), Redis 7.4 and `redis-benchmark` run
> from the official `redis:7.4` Docker image. JediCore runs on the host JVM. The
> benchmark client is in a container, so it reaches both servers over the
> docker→host bridge — see the caveat below.

## 1. `redis-benchmark` — JediCore vs real Redis 7.4

Same client, same machine, same network path (`redis-benchmark` in a container →
`host.docker.internal:<port>`); JediCore on the host port, real Redis on a
published container port.

### Non-pipelined (`-n 100000 -c 50`)

| Command | JediCore rps | JediCore p50 | Redis 7.4 rps | Redis p50 |
|---------|-------------:|-------------:|--------------:|----------:|
| SET     | 12130 | 2.98 ms | 13055 | 3.27 ms |
| GET     | 11648 | 2.80 ms | 13635 | 3.16 ms |
| INCR    | 14067 | 2.70 ms | 13156 | 3.29 ms |
| LPUSH   | 13795 | 2.74 ms | 11168 | 3.40 ms |
| RPUSH   | 13889 | 2.73 ms | 12888 | 3.33 ms |
| LPOP    | 13820 | 2.71 ms | 12473 | 3.43 ms |
| RPOP    | 11692 | 2.82 ms | 12687 | 3.42 ms |
| SADD    | 13885 | 2.71 ms | 11117 | 3.45 ms |
| SPOP    | 13635 | 2.82 ms | 12726 | 3.42 ms |
| HSET    | 13488 | 2.79 ms | 12930 | 3.35 ms |
| ZADD    | 11848 | 2.84 ms | 12539 | 3.45 ms |

**Honest reading.** Without pipelining the **docker→host network round-trip
(~3 ms p50) dominates**, so both servers are network-bound at ~12k rps and are
statistically indistinguishable — this measures the *path*, not the engine. (On a
native loopback connection both go far higher; this machine's benchmark client is
containerised, which is the realistic setup available here.)

### Pipelined (`-n 200000 -c 50 -P 16`)

| Command | JediCore rps | Redis 7.4 rps | Ratio (Redis ÷ JediCore) |
|---------|-------------:|--------------:|-------------------------:|
| SET  | 90498  | 163934 | 1.81× |
| GET  | 96154  | 178094 | 1.85× |
| INCR | 92550  | 180832 | 1.95× |

**Honest reading.** With the network amortised by pipelining, the engine gap
shows: **real Redis is ~1.8–2× faster.** That is expected and, for a from-scratch
JVM clone, respectable. The gap comes from native C vs the JVM (no GC, no object
headers), Redis's hand-tuned event loop and zero-copy RESP parsing, and JediCore's
extra per-command hop (Netty I/O thread → single command thread) plus more
allocation per request. Closing it would mean a sharded command loop and an
allocation-free codec — both deliberately deferred (see ARCHITECTURE.md).

## 2. JMH microbenchmarks (engine only, no network)

These isolate the cost the command thread actually pays per operation. Run with
`./gradlew :jediscore-benchmarks:jmh -Pjmh.include=<Name>`.

| Benchmark | Result | Notes |
|-----------|--------|-------|
| Skiplist rank vs TreeMap | ~27× faster | the custom `SkipList` ZSET index vs a `TreeMap` baseline |
| `BGSAVE` snapshot pause | ≈ 0.81 ms / 10k keys | the deep-copy pause; serialize ≈ 0.63 ms |
| AOF append (`appendfsync always`) | ≈ 2.3k ops/s | one `fsync` per command (disk-bound) |
| AOF append (`everysec` / `no`) | ≈ 253k / 258k ops/s | OS-buffered |
| Pub/Sub `PUBLISH` fan-out | 0.028 µs (1) → 6.9 µs (1000 subs) | ~7 ns per subscriber |
| WATCH per-write touch | ≈ 2 ns (no watchers) → 21 ns | O(args), independent of watcher count |
| Blocking readiness signal | ≈ 1.3 ns (none blocked) → 49 ns | the per-write gate is essentially free |
| `EVAL` (warm cache) | ≈ 0.65 µs (trivial) / 1.14 µs (+`redis.call`) | compiled-chunk cache |
| Replication propagate | ≈ 0.5 µs / write | encode + backlog + fan-out; zero when no replica attached |

**Reading.** The data-structure and dispatch costs are sub-µs to low-µs — the
engine is not the bottleneck at the per-op level; the network and the JVM/codec
overhead are, as the pipelined `redis-benchmark` confirms.

## Reproducing

```sh
# JMH smoke (CI-fast settings)
./gradlew :jediscore-benchmarks:jmh

# redis-benchmark vs real Redis (start JediCore with protected-mode off so a
# non-loopback benchmark client is allowed)
java -cp 'jediscore-server/build/install/jediscore/lib/*' \
     dev.jediscore.server.JediCoreServer 0.0.0.0:6399 --protected-mode no
docker run --rm redis:7.4 redis-benchmark -h host.docker.internal -p 6399 \
     -n 100000 -c 50 -P 16 -t set,get,incr -q
```
