# Benchmarks

Every number here was measured on the machine described below. Nothing is estimated,
extrapolated or taken from anywhere else. If you want to check them, run
`./benchmarks/run-benchmarks.sh` and compare — the raw `redis-benchmark` output is
committed under `benchmarks/results/raw/`.

## Method

**The tool is `redis-benchmark`**, the official client shipped with Redis. That is a
deliberate choice: a load generator written by the same person who wrote the server
invites the obvious suspicion, and using a third-party tool means all three subjects are
measured identically by code that has no stake in the outcome.

**Three subjects, one host, native:**

| Subject | What it is |
|---|---|
| `resp-server (virtual threads)` | this server, one virtual thread per connection |
| `resp-server (event loop)` | this server, single-threaded NIO selector |
| `Redis` | the real thing, 8.0.5 |

All three run natively over loopback. Containerising all three would also be fair, but
would add network overhead to every measurement for no gain in comparability.

**Controls:**

- A **warmup pass is run and discarded** against every subject. The JVM starts
  interpreted and only compiles hot methods after some thousands of invocations, so a
  first measured run times the interpreter and the JIT rather than the server. Redis gets
  the same warmup for symmetry, though a C binary does not need one.
- **Three measured runs** per configuration; the tables report the **median**, not the
  best. A best-of-N reports the luckiest scheduling the machine produced, not what the
  server does.
- **The spread is printed next to every figure** — the gap between the fastest and slowest
  run as a percentage of the median. This matters more than it looks: several comparisons
  below are inside their own noise and should not be read as differences at all.
- The keyspace is flushed between runs.

## Environment

```
date            : 2026-09-10 04:38:46 UTC
kernel          : Linux 6.18.33.2-microsoft-standard-WSL2
distro          : Ubuntu 26.04 LTS
cpu             : 11th Gen Intel(R) Core(TM) i5-1135G7 @ 2.40GHz
cpu cores       : 8
memory          : 3.7Gi
java            : openjdk version "21.0.12" 2026-07-21
jvm flags       : -XX:+UseG1GC
redis-server    : Redis server v=8.0.5
redis-benchmark : redis-benchmark 8.0.5
runs per config : 3 (plus one discarded warmup)
transport       : loopback, all subjects native on one host
```

This is a **laptop running WSL2 with 3.7 GB of RAM**, not a server. That matters, and it
is the first thing said in the limitations section below.

## Results

### Mixed commands, 50 connections, no pipelining

The most representative configuration: ten different commands, a normal connection count,
one request in flight at a time per connection.

| test | subject | ops/sec (median) | spread | p50 ms | p95 ms | p99 ms |
|---|---|---:|---:|---:|---:|---:|
| GET | virtual threads | 53,079 | ±27.7% | 0.503 | 0.743 | 1.015 |
| GET | event loop | 123,153 | ±7.7% | 0.207 | 0.303 | 0.471 |
| GET | Redis | 129,032 | ±4.8% | 0.199 | 0.295 | 0.431 |
| SET | virtual threads | 53,619 | ±24.5% | 0.495 | 0.743 | 0.967 |
| SET | event loop | 123,609 | ±11.2% | 0.207 | 0.295 | 0.487 |
| SET | Redis | 124,688 | ±19.1% | 0.207 | 0.303 | 0.447 |
| INCR | virtual threads | 54,585 | ±19.3% | 0.487 | 0.719 | 0.935 |
| INCR | event loop | 125,000 | ±11.2% | 0.207 | 0.415 | 0.687 |
| INCR | Redis | 127,877 | ±24.9% | 0.207 | 0.319 | 0.463 |
| LPUSH | virtual threads | 54,705 | ±19.9% | 0.487 | 0.711 | 0.927 |
| LPUSH | event loop | 127,714 | ±25.3% | 0.199 | 0.391 | 0.583 |
| LPUSH | Redis | 119,474 | **±56.5%** | 0.215 | 0.343 | 0.527 |
| RPOP | virtual threads | 53,447 | ±22.4% | 0.495 | 0.743 | 0.991 |
| RPOP | event loop | 121,507 | ±15.9% | 0.207 | 0.351 | 0.591 |
| RPOP | Redis | 130,208 | ±26.1% | 0.199 | 0.287 | 0.447 |
| SADD | virtual threads | 54,142 | ±22.4% | 0.495 | 0.727 | 0.959 |
| SADD | event loop | 125,628 | ±7.0% | 0.207 | 0.343 | 0.503 |
| SADD | Redis | 126,103 | ±10.5% | 0.207 | 0.327 | 0.527 |
| HSET | virtual threads | 53,362 | ±34.6% | 0.503 | 0.735 | 0.959 |
| HSET | event loop | 127,877 | ±14.2% | 0.199 | 0.319 | 0.487 |
| HSET | Redis | 127,551 | ±29.9% | 0.199 | 0.295 | 0.415 |
| ZADD | virtual threads | 54,377 | ±23.5% | 0.495 | 0.719 | 0.943 |
| ZADD | event loop | 127,877 | ±7.9% | 0.199 | 0.311 | 0.511 |
| ZADD | Redis | 133,333 | ±1.8% | 0.199 | 0.311 | 0.447 |
| MSET (10 keys) | virtual threads | 52,604 | ±23.2% | 0.511 | 0.767 | 1.151 |
| MSET (10 keys) | event loop | 132,979 | ±5.3% | 0.215 | 0.439 | 0.695 |
| MSET (10 keys) | Redis | 141,443 | ±2.6% | 0.207 | 0.359 | 0.543 |
| PING_INLINE | virtual threads | 55,127 | ±3.7% | 0.479 | 0.711 | 0.927 |
| PING_INLINE | event loop | 114,025 | ±4.8% | 0.223 | 0.335 | 0.511 |
| PING_INLINE | Redis | 122,850 | ±4.8% | 0.207 | 0.311 | 0.431 |

### 500 connections, no pipelining

The configuration where thread-per-connection historically fell over.

| test | subject | ops/sec (median) | spread | p50 ms | p95 ms | p99 ms |
|---|---|---:|---:|---:|---:|---:|
| GET | virtual threads | 55,279 | ±4.1% | 4.511 | 6.751 | 7.855 |
| GET | event loop | 126,743 | ±1.0% | 1.943 | 2.479 | 3.231 |
| GET | Redis | 118,624 | **±23.8%** | 2.079 | 2.583 | 3.111 |
| SET | virtual threads | 55,586 | ±10.0% | 4.591 | 6.855 | 7.975 |
| SET | event loop | 123,609 | ±13.3% | 1.991 | 2.583 | 3.655 |
| SET | Redis | 117,647 | ±6.1% | 2.087 | 2.663 | 3.183 |

### 50 connections, pipeline depth 16

| test | subject | ops/sec (median) | spread | p50 ms | p95 ms | p99 ms |
|---|---|---:|---:|---:|---:|---:|
| GET | virtual threads | 737,463 | ±14.0% | 0.583 | 0.895 | 1.591 |
| GET | event loop | 936,330 | ±4.2% | 0.823 | 1.231 | 1.887 |
| GET | Redis | 1,607,717 | ±5.7% | 0.399 | 0.607 | 0.863 |
| SET | virtual threads | 761,035 | ±28.4% | 0.559 | 0.959 | 1.519 |
| SET | event loop | 917,431 | ±8.1% | 0.839 | 1.159 | 2.039 |
| SET | Redis | 1,373,626 | ±8.7% | 0.479 | 0.695 | 1.079 |

### Relative to Redis

| config | test | virtual threads | event loop |
|---|---|---:|---:|
| mixed-c50 | GET | 41% | 95% |
| mixed-c50 | SET | 43% | 99% |
| mixed-c50 | INCR | 43% | 98% |
| mixed-c50 | LPUSH | 46% | 107% |
| mixed-c50 | RPOP | 41% | 93% |
| mixed-c50 | SADD | 43% | 100% |
| mixed-c50 | HSET | 42% | 100% |
| mixed-c50 | ZADD | 41% | 96% |
| mixed-c50 | MSET | 37% | 94% |
| connections-c500 | GET | 47% | 107% |
| connections-c500 | SET | 47% | 105% |
| pipelined-P16 | GET | 46% | 58% |
| pipelined-P16 | SET | 55% | 67% |

## What the numbers say

### The event loop is roughly 2.3× the virtual-thread mode, unpipelined

This is the clearest result in the data and it holds across every command and both
connection counts. It is not close and it is not inside the noise.

### The event loop reaches parity with Redis on unpipelined workloads

93–107% across the mixed configuration. **This is not a claim that this server is as fast
as Redis**, and reading it that way would be wrong. What it actually shows is that at this
request rate, on this machine, the cost is dominated by syscalls and the network stack
rather than by anything either server does with the data. Both spend most of their time in
`read` and `write`; the C and the Java implementations of "put bytes in a hash map"
disappear underneath that.

The cases above 100% are inside the measurement noise — Redis's LPUSH run varied by ±56.5%
and its 500-connection GET by ±23.8%. Treat 93–107% as "indistinguishable at this rate",
not as a ranking.

### Under pipelining, Redis pulls clearly ahead

At depth 16 the picture changes completely: Redis reaches 1.4–1.6M ops/sec, the event loop
917k–936k, virtual threads 737k–761k. Redis is 1.5–1.7× the event loop.

This is the honest and expected result, and it is the configuration that actually measures
the server rather than the syscall path. Pipelining amortises the per-syscall cost over
sixteen commands, so what remains is per-command work — parsing, hashing, allocation — and
there C with a decade of optimisation beats a straightforward Java implementation. If any
configuration here reflects "how good is the code", it is this one, and this server loses.

### Why virtual threads plateau at ~55k

The virtual-thread number is suspiciously flat: ~53–55k ops/sec for every command, at both
50 and 500 connections. A number that ignores both the work being done and the concurrency
offered is a bottleneck in the plumbing, not in the commands.

The pipelining figures identify it. Going from no pipelining to depth 16:

| subject | unpipelined | P16 | ratio |
|---|---:|---:|---:|
| virtual threads | 53,619 | 761,035 | **14.2×** |
| event loop | 123,609 | 917,431 | 7.4× |
| Redis | 124,688 | 1,373,626 | 11.0× |

Virtual threads gain the *most* from pipelining. Pipelining changes one thing: how many
commands arrive per `read()`. At depth 16 the same work costs a sixteenth of the reads. So
whatever limits the unpipelined case scales with the number of read operations, not with
the number of commands — which points at the per-read cost of the blocking path: a syscall
pair per command, plus parking and unparking the virtual thread every time a `read` finds
nothing ready.

That is a hypothesis supported by the data, not a proven cause. Isolating it properly would
mean profiling the two paths — with async-profiler, or by counting context switches — and
that has not been done. It is stated here as an inference so that a reader can judge it,
rather than presented as a finding.

### What the p50/p99 columns show

The event loop's tail is tight: at 500 connections, p50 1.94ms and p99 3.23ms, so p99 is
about 1.7× p50. The virtual-thread mode at the same load is p50 4.51ms and p99 7.86ms.
Both scale predictably from the 50-connection case; neither collapses.

## What these benchmarks do NOT prove

This section is as important as the results.

- **Nothing about a real deployment.** Loopback on one laptop under WSL2 with 3.7 GB of
  RAM. No network, no NIC interrupts, no other tenants, no sustained load. A real
  deployment would change every number here.
- **`redis-benchmark` is itself a bottleneck.** At a million-plus ops/sec the load
  generator competes with the server for the same 8 cores. Some of what is measured above
  is the benchmark tool, and that ceiling applies to all three subjects but not equally.
- **They do not prove this server is as fast as Redis.** The pipelined configuration —
  the one that actually isolates per-command cost — shows Redis 1.5–1.7× ahead.
- **They say nothing about behaviour under memory pressure**, with large values, with
  millions of keys, or over hours. Every run is short and starts from an empty keyspace.
- **Several individual comparisons are inside their own noise.** Any figure with a spread
  above ±20% should be read as "about the same", not as a difference. Redis's ±56.5% LPUSH
  run is the clearest example.
- **Only one machine, one JDK, one Redis version, one day.** No claim of generality.

## Reproducing

```bash
mvn package
./benchmarks/run-benchmarks.sh 3      # runs per configuration
python3 benchmarks/summarise.py
```

Outputs land in `benchmarks/results/`: `results.csv` (all runs), `raw/` (unmodified
`redis-benchmark` CSV per run), `environment.txt`, `summary.md`.
