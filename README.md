# resp-server

A Redis-compatible server written from scratch in Java 21, with **zero runtime
dependencies** — no Netty, no client libraries, just the JDK. The official `redis-cli`
connects to it and cannot tell the difference for the 63 commands it implements.

It is built **twice**: once with a virtual thread per connection, once as a single-threaded
NIO event loop, behind one interface over one command layer and one keyspace — then
benchmarked against each other and against real Redis 8.0.5.

```bash
mvn verify && java -jar target/resp-server.jar --port 6380
redis-cli -p 6380
```

```
127.0.0.1:6380> SET greeting "hello world"
OK
127.0.0.1:6380> ZADD board 42 alice 17 bob
(integer) 2
127.0.0.1:6380> ZRANGE board 0 -1 WITHSCORES
1) "bob"
2) "17"
3) "alice"
4) "42"
127.0.0.1:6380> ZRANK board alice
(integer) 1
127.0.0.1:6380> SET session token EX 60
OK
127.0.0.1:6380> TTL session
(integer) 60
```

Switch concurrency model with a flag:

```bash
java -jar target/resp-server.jar --mode virtual     # a virtual thread per connection
java -jar target/resp-server.jar --mode eventloop   # one thread, NIO selector
```

---

## The question this was built to answer

Java 21 shipped virtual threads, and the claim attached to them is that
thread-per-connection — the design non-blocking I/O was invented to escape — is viable
again. That is a claim you can measure.

So the server exists in two forms sharing everything above the transport, and the **entire
52-test compatibility suite runs against both**, so the comparison is between concurrency
models rather than between two different servers.

### What the measurements showed

Official `redis-benchmark`, warmup discarded, median of 3 runs, spread reported. Full
method, environment and caveats in **[docs/benchmarks.md](docs/benchmarks.md)**.

**Unpipelined, 50 connections:**

| subject | ops/sec (median) | p99 |
|---|---:|---:|
| resp-server, event loop | 123,609 | 0.487 ms |
| resp-server, virtual threads | 53,619 | 0.967 ms |
| Redis 8.0.5 | 124,688 | 0.447 ms |

**Pipeline depth 16:**

| subject | ops/sec (median) |
|---|---:|
| Redis 8.0.5 | 1,373,626 |
| resp-server, event loop | 917,431 |
| resp-server, virtual threads | 761,035 |

**The honest reading.** Unpipelined, the event loop is ~2.3× the virtual-thread mode and is
within measurement noise of Redis — but that is because at that rate *both* servers are
bottlenecked on syscalls, not because the implementations are equivalent. Pipelining
amortises the syscalls away, and there **Redis is 1.5–1.7× ahead**. That is the
configuration that actually measures the server, and this one loses it.

Virtual threads made thread-per-connection **viable**, not **competitive**. That is a
narrower claim than the marketing, and it is what the data supports.

---

## What is worth reading in here

Writing a Redis clone is a well-worn exercise; most stop at a toy that echoes `PONG`. These
are the parts those skip.

**An incremental parser, written first.** TCP is a byte stream, not a message stream. One
`read()` can return half a command or thirty-seven of them. `RespReader.tryParse` either
consumes exactly one complete frame or leaves the buffer untouched — proven by a test that
feeds every frame **one byte at a time** and requires it to return nothing until the final
byte. Pipelining, the event loop and crash recovery all fell out of that decision for free.
([ADR 0003](docs/adr/0003-incremental-parser.md))

**A hand-written skip list with span tracking**, so `ZRANK` is O(log n) rather than a scan.
A span bug is *silent* — ordering stays correct and only `ZRANK` lies — so the tests compare
against a `TreeSet` oracle on **rank**, not just order.
([ADR 0004](docs/adr/0004-skiplist-for-sorted-sets.md))

**Partial writes handled properly.** `write()` returns how many bytes the kernel accepted,
which can be fewer than offered. The remainder is buffered and `OP_WRITE` registered — and
then **deregistered**, because leaving it makes `select()` spin at 100% CPU while the server
still appears to work. No functional test catches that, so there is one that measures the
event loop thread's CPU time while idle.

**Durability proven by killing the process.** Not a mocked failure:
`scripts/crash-recovery-demo.sh` sends `SIGKILL` mid-write, restarts, and verifies.

**Compatibility proven by a third-party client.** The suite drives the server with **Jedis**,
written by people who have never seen this code. It found a real bug: Jedis sends the legacy
standalone `SETNX`, which the server did not implement.

---

## Architecture

```
              redis-cli / redis-benchmark / Jedis
                          │  TCP
              ┌───────────┴───────────┐
              │   RedisServer (iface) │
     ┌────────┴────────┐     ┌────────┴─────────┐
     │VirtualThread     │     │  EventLoop       │
     │Server            │     │  Server          │
     │1 vthread/conn    │     │  1 thread, NIO   │
     │blocking I/O      │     │  selector        │
     │needs locking     │     │  no locking      │
     └────────┬────────┘     └────────┬─────────┘
              └───────────┬───────────┘
                    RespReader  ── incremental: whole frame or nothing
                          │
                  CommandExecutor  ── dirty-counter gate → AOF
                          │
                   CommandRegistry  ── arity + type validation, never throws
                          │
                      Database  ── ConcurrentHashMap, atomic per-key compute
      ┌──────────┬──────────┼──────────┬─────────────┐
  RedisString RedisList RedisHash  RedisSet  RedisSortedSet
                                              (HashMap + SkipList)
                          │
                   ExpiryManager  ── lazy on read + adaptive background sweep
                          │
                     AofWriter → appendonly.aof → AofLoader at startup
```

---

## Commands

| Group | Commands |
|---|---|
| Connection | `PING` `ECHO` `QUIT` `SELECT` `COMMAND` `CLIENT` `INFO` |
| Generic | `DEL` `EXISTS` `TYPE` `KEYS` `DBSIZE` `FLUSHALL` `EXPIRE` `PEXPIRE` `TTL` `PTTL` `PERSIST` |
| String | `SET` (`EX` `PX` `NX` `XX` `KEEPTTL`) `GET` `GETSET` `GETDEL` `SETNX` `SETEX` `PSETEX` `MSET` `MGET` `INCR` `DECR` `INCRBY` `DECRBY` `APPEND` `STRLEN` |
| List | `LPUSH` `RPUSH` `LPOP` `RPOP` `LRANGE` `LLEN` `LINDEX` |
| Hash | `HSET` `HMSET` `HGET` `HDEL` `HGETALL` `HEXISTS` `HLEN` `HKEYS` `HVALS` |
| Set | `SADD` `SREM` `SMEMBERS` `SISMEMBER` `SCARD` |
| Sorted set | `ZADD` `ZINCRBY` `ZSCORE` `ZRANGE` `ZREVRANGE` `ZRANK` `ZREVRANK` `ZREM` `ZCARD` |

Inline commands work too, so a raw socket is enough:

```bash
printf 'PING\nECHO hello\n' | nc 127.0.0.1 6380
```

---

## Persistence and crash recovery

Every command that actually changes the dataset is appended to a file as the RESP array the
client sent. Replaying rebuilds the keyspace.

**Only real mutations are recorded.** `SET k v NX` against an existing key changes nothing,
but replaying it into an empty keyspace *would* create the key — recovery producing a
different dataset from the one that was saved. A dirty counter is sampled either side of
each command and only a genuine change is persisted. Redis solves the same problem the same
way. ([ADR 0005](docs/adr/0005-aof-persistence.md))

fsync policy is configurable: `always`, `everysec` (default), `no`.

```
$ ./scripts/crash-recovery-demo.sh

=== 3. SIGKILL the server while more writes are still in flight ===
    killed pid 9267 with SIGKILL -- no hook, no flush, no close
    still running     : no
    aof size          : 851760 bytes

=== 4. restart ===
  restored    19021 commands, 19021 keys

=== 5. verify ===
    keys before crash : 5004      key:1        : value:1
    keys after restart: 19021     queue        : a,b,c
                                  profile.name : preetham
                                  board score  : 42

PASS  20 sampled keys all present, 19021 >= 5004, server writable again
```

`SIGKILL` cannot be caught — no shutdown hook, no flush, no close. Anything that survived
was already on disk. A record cut off mid-append is discarded and the file truncated to the
last complete frame, because leaving the fragment would corrupt everything written after it.

---

## Testing

```bash
mvn -B verify
```

**267 tests: 142 unit, 125 integration.**

| Suite | What it establishes |
|---|---|
| `RespCodecTest` | Every RESP2 type encodes and parses; malformed input rejected rather than guessed at; encode→parse round-trips including binary and UTF-8 |
| `IncrementalParseTest` | Frames survive arbitrary fragmentation, **fed one byte at a time**; an incomplete parse consumes nothing; all 30 split points of a `SET`; inline commands with bare LF |
| `DatabaseTest` | Expiry on a fake clock; `INCR` rejects non-canonical numbers; **100 virtual threads × 1000 increments lose nothing** |
| `SortedSetTest` | Skip list agrees with a `TreeSet` oracle on order, membership **and rank**; span invariants verified independently |
| `ExpiryManagerTest` | Unread keys still reclaimed; live keys untouched; cursor covers the keyspace; cycle work is bounded |
| `GlobMatcherTest` | Redis glob syntax; a pathological pattern stays fast rather than backtracking exponentially |
| `CompatibilitySuite` | **Jedis** drives the real server across all five types — run twice, **once per concurrency model** |
| `EventLoopStressIT` | 16 MB replies; a slow reader pinned in `OP_WRITE`; **a CPU-time assertion that catches a spinning selector**; 200 concurrent clients; RST disconnects; 10,000-command pipeline checked reply by reply |
| `AofPersistenceIT` | Restart; replayed deletions; restored TTLs; truncated tail repaired; **a failed `SETNX` is not persisted** |

---

## Documentation

| Document | For |
|---|---|
| **[docs/LEARNING_GUIDE.md](docs/LEARNING_GUIDE.md)** | The system explained from first principles, start to finish — architecture, request lifecycle, every subsystem, and what to learn before each part |
| **[docs/INTERVIEW_GUIDE.md](docs/INTERVIEW_GUIDE.md)** | Reasoning chains rather than memorised answers |
| **[docs/benchmarks.md](docs/benchmarks.md)** | Method, environment, results, and an explicit list of what they do *not* prove |
| **[docs/adr/](docs/adr/)** | Six decision records: why two models, why no dependencies, why an incremental parser, why a skip list, why AOF, why a cursor for expiry |

---

## Running it

```
-p, --port <n>            port to listen on (default 6380)
-b, --bind <addr>         address to bind (default 127.0.0.1)
-m, --mode <mode>         virtual (default) | eventloop
    --max-clients <n>     maximum concurrent connections (default 10000)
    --appendonly <path>   enable AOF persistence at this path
    --fsync <policy>      always | everysec (default) | no
    --expiry-interval <n> active expiry cycle interval in ms (default 100)
-v, --verbose             log each connection
```

Docker, including real Redis alongside for comparison:

```bash
docker compose up --build
redis-cli -p 6380   # virtual threads
redis-cli -p 6381   # event loop
redis-cli -p 6379   # real Redis
```

---

## What this is not

Stated plainly, because a project that overclaims is worse than one that does less
honestly.

There is **no** replication, clustering, transactions (`MULTI`), Lua scripting, pub/sub,
blocking commands (`BLPOP`), `SCAN` cursors, RESP3, or RDB snapshots. One database rather
than sixteen. It is a learning and benchmarking project, not a Redis replacement.

`INFO` reports `redis_version:7.0.0` as a compatibility shim, because clients gate feature
detection on that field; `server_name` and `resp_server_version` alongside it say what this
actually is.

### Known limitations

- **`MSET` is not atomic across keys** under the virtual-thread model. Single-key commands
  are atomic — the store does read-modify-write inside `ConcurrentHashMap.compute` — but
  multi-key commands write one key at a time. Real Redis avoids this by being
  single-threaded. The event-loop mode does not have the problem.
- **The AOF is never rewritten.** It grows with the number of writes, not the size of the
  data: a counter incremented a million times is one key but a million records. This is the
  largest gap in the persistence story.
- **AOF appends serialise on one lock**, because the log must record the same order the
  keyspace applied. Under many concurrent writers that is a real bottleneck.
- **Active expiry uses a rotating cursor, not random sampling.** `ConcurrentHashMap` has no
  O(1) random selection and a parallel TTL index would add hot-path bookkeeping to speed up
  a background task. Same eventual coverage, bounded work, some wasted looks at keys with
  no TTL.
- **The virtual-thread mode's ~55k ops/sec plateau is explained by inference, not by
  profiling.** See `docs/benchmarks.md`.

---

## Requirements

Java 21 or later — virtual threads and record patterns are both used, and neither is
optional to the design.
