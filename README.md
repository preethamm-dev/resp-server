# resp-server

A Redis-compatible server written from scratch in Java 21, with **zero runtime
dependencies** — no Netty, no client libraries, just the JDK. The official `redis-cli`
connects to it and cannot tell the difference for the commands it implements.

> **Work in progress.** Days 1 and 2 of 3 are complete: the RESP2 protocol, all five data
> types, key expiry, AOF persistence with crash recovery, and the virtual-thread server.
> Day 3 adds the single-threaded NIO event loop and benchmarks against real Redis.

## Try it

```bash
mvn -B verify
java -jar target/resp-server.jar --port 6380

# in another terminal, with the official client
redis-cli -p 6380
```

```
127.0.0.1:6380> SET greeting "hello world"
OK
127.0.0.1:6380> APPEND greeting "!"
(integer) 12
127.0.0.1:6380> RPUSH queue a b c
(integer) 3
127.0.0.1:6380> LRANGE queue 0 -1
1) "a"
2) "b"
3) "c"
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

It also speaks the inline protocol, so a raw socket works:

```bash
printf 'PING\nECHO hello\n' | nc 127.0.0.1 6380
```

## Why this exists

Writing a Redis clone is a well-worn exercise; most stop at a toy that echoes `PONG`.
The parts of this one worth reading are the parts those skip:

- **An incremental parser.** TCP is a byte stream, not a message stream. One `read()`
  can return half a command or thirty-seven of them. The parser either consumes exactly
  one complete frame or leaves the buffer untouched — proven by a test that feeds every
  frame **one byte at a time** and requires it to return nothing until the final byte.
  Pipelining then falls out for free.
- **A hand-written skip list with span tracking**, so `ZRANK` is O(log n) rather than a
  scan — tested against a `TreeSet` oracle because a randomised structure cannot be
  checked with fixed expectations.
- **Durability proven by killing the process.** Not a mocked failure: `SIGKILL`, mid-write,
  then restart and check.
- **Two concurrency models, benchmarked against each other** *(day 3)*. The same command
  layer and keyspace sit behind both a virtual-thread-per-connection server and a
  single-threaded NIO event loop, so a benchmark isolates exactly one variable.
- **Compatibility proven by a third-party client.** The integration suite drives the
  server with **Jedis**, written by people who have never seen this code. That is a much
  stronger claim than "my parser agrees with my writer".

## Architecture

```
              redis-cli / redis-benchmark / Jedis
                          │  TCP
              ┌───────────┴───────────┐
              │   RedisServer (iface) │
     ┌────────┴────────┐     ┌────────┴─────────┐
     │VirtualThread     │     │  EventLoop       │
     │Server            │     │  Server (day 3)  │
     │1 vthread/conn    │     │  single-threaded │
     └────────┬────────┘     └────────┬─────────┘
              └───────────┬───────────┘
                    RespReader  ── incremental, never blocks
                          │
                  CommandExecutor  ── persists only real mutations
                          │
                   CommandRegistry  ── arity + type validation
                          │
                      Database  ── ConcurrentHashMap keyspace,
                          │          atomic per-key compute
      ┌───────────┬───────┴───────┬────────────┬─────────────┐
  RedisString  RedisList     RedisHash    RedisSet    RedisSortedSet
                                                       (hash + skip list)
                          │
                   ExpiryManager  ── lazy on read, adaptive sampling in background
                          │
                     AofWriter → appendonly.aof → AofLoader on restart
```

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

## Sorted sets: two structures, one collection

A sorted set holds a `HashMap` **and** a skip list over the same data, which is how Redis
implements it. Neither alone works: `ZSCORE` must be O(1), which rules out a skip list
alone; `ZRANGE` and `ZRANK` need ordering, which a hash map cannot give.

The skip list is written by hand rather than delegating to `TreeMap`, because of `ZRANK`.
A balanced tree answers "how many elements precede this one" in O(n) unless every node
also stores a subtree size, which must then be repaired up the whole path on each
rotation. A skip list stores a **span** on each forward pointer — how many nodes it jumps
— so rank falls out of the same descent that finds the element, for free.

Span maintenance is also the easiest thing to get subtly wrong, and the failure is quiet:
ordering stays correct, `ZRANGE` looks fine, and only `ZRANK` returns wrong numbers. So
the tests compare against a `TreeSet` oracle on **rank**, not just order, and
`checkInvariants` independently verifies that every span equals the distance it claims.

## Expiry

Two mechanisms, because either alone is insufficient:

- **Lazy** — a key past its deadline reads as absent and is deleted on access. Correct,
  but a key nobody reads again is never reclaimed.
- **Active** — a background cycle samples 20 keys, deletes the expired ones, and if more
  than a quarter of the sample was expired, immediately goes again (capped at 16 rounds).
  The feedback loop means the reaper costs almost nothing when little is expiring and
  works hard exactly when a lot is, without ever scanning the whole keyspace.

Redis samples randomly from a dedicated dictionary of keys that have a TTL. This server
uses a rotating cursor over the keyspace instead: `ConcurrentHashMap` offers no O(1)
random selection, and maintaining a parallel index would mean extra bookkeeping on every
`SET` and `DEL` to speed up a background task. The cursor gives the same eventual coverage
with bounded work per cycle, at the cost of some wasted looks at keys with no TTL.

## Persistence and crash recovery

Every command that actually changes the dataset is appended to a file, as the RESP array
the client sent. Replaying them rebuilds the keyspace.

**Only real mutations are recorded.** `SET k v NX` against an existing key changes
nothing, but replaying it into an empty keyspace *would* create the key — so recovery
would produce a different dataset from the one that was saved. A dirty counter on the
keyspace is sampled before and after each command, and the command is persisted only if
it moved. Redis solves the same problem the same way.

fsync policy is configurable: `always` (nothing acknowledged is ever lost, slow),
`everysec` (default; at most one second lost to a power cut, nothing lost to a process
crash), or `no`.

### The crash test

`scripts/crash-recovery-demo.sh` kills the server with `SIGKILL` while writes are in
flight, restarts it, and verifies. `SIGKILL` cannot be caught: no shutdown hook, no flush,
no close. Anything that survives does so because it was already on disk.

```
=== 3. SIGKILL the server while more writes are still in flight ===
    killed pid 9267 with SIGKILL -- no hook, no flush, no close
    still running     : no
    aof size          : 851760 bytes

=== 4. restart ===
  restored    19021 commands, 19021 keys

=== 5. verify ===
    keys before crash : 5004
    keys after restart: 19021
    key:1             : value:1
    key:5000          : value:5000
    queue             : a,b,c
    profile.name      : preetham
    tags              : java,redis
    board score       : 42

PASS  20 sampled keys all present, 19021 >= 5004, server writable again
```

The count is higher after the restart than before it because the writer was still pushing
keys when the process died, and those had been fsynced too.

A record cut off mid-append is discarded and the file truncated to the last complete
frame — otherwise the fragment would be parsed as the start of whatever got written next
and corrupt everything after it. The parser makes this easy for the reason it was built
that way on day one: it already distinguishes "not enough bytes yet" from "malformed", and
at the end of a file the former simply means "this record was never finished".

## What this is not

Stated plainly, because a portfolio project that overclaims is worse than one that does
less honestly. There is **no** replication, clustering, transactions (`MULTI`), Lua
scripting, pub/sub, blocking commands (`BLPOP`), `SCAN` cursors, RESP3, or RDB snapshots.
One database rather than sixteen. It is a learning and benchmarking project, not a Redis
replacement.

`INFO` reports `redis_version:7.0.0` as a compatibility shim, because clients gate feature
detection on that field. The adjacent `server_name` and `resp_server_version` say what it
actually is.

### Known limitations

- **`MSET` is not atomic across keys.** Single-key commands are atomic — the store does
  read-modify-write inside `ConcurrentHashMap.compute` — but multi-key commands write one
  key at a time, so a concurrent reader can see a half-applied batch. Real Redis avoids
  this by being single-threaded. Fixing it here would need a global lock, which would cost
  more than it buys; the event-loop mode on day 3 does not have the problem at all.
- **The AOF is never rewritten.** It grows with the number of writes, not the size of the
  data: a counter incremented a million times is one key but a million records. Redis
  periodically rewrites the log as the shortest command sequence that recreates the
  current state. That is not implemented.
- **AOF appends are serialised on one lock**, because the log has to record the same order
  the keyspace applied. Under many concurrent writers that lock is a real bottleneck —
  another cost single-threaded Redis does not pay.

## Testing

```bash
mvn -B verify
```

**204 tests**: 142 unit, 62 integration.

| Suite | What it establishes |
|---|---|
| `RespCodecTest` | Every RESP2 type encodes and parses; malformed input is rejected rather than guessed at; encode→parse round-trips, including binary payloads and UTF-8 |
| `IncrementalParseTest` | Frames survive arbitrary fragmentation, fed one byte at a time; an incomplete parse consumes nothing; pipelined batches drain fully; partial tails are retained; inline commands work with bare LF |
| `DatabaseTest` | Expiry semantics on a fake clock; `INCR` rejects non-canonical numbers; 100 virtual threads incrementing one key lose no updates |
| `SortedSetTest` | Skip list agrees with a `TreeSet` oracle on order, membership **and rank** over randomised workloads; span invariants verified independently; survives a full insert-then-delete cycle |
| `ExpiryManagerTest` | Unread keys are still reclaimed; live keys are untouched; the cursor covers the whole keyspace; one cycle's work is bounded |
| `GlobMatcherTest` | Redis glob syntax, including that a pathological pattern stays fast rather than backtracking exponentially |
| `ServerCompatibilityIT` | **Jedis** drives the real server over a socket across all five types: binary safety, 1 MB values, unicode, TTL conventions, `WRONGTYPE` for every mismatched pair, 500-command pipelines |
| `AofPersistenceIT` | Data survives restart; deletions are replayed; a failed `SETNX` is *not* persisted; a truncated trailing record is discarded and the file repaired; every fsync policy produces a replayable file |

Expiry is tested with an injected clock rather than `Thread.sleep`, which keeps the suite
fast and removes a class of flakiness on loaded CI machines.

## Options

```
-p, --port <n>            port to listen on (default 6380)
-b, --bind <addr>         address to bind (default 127.0.0.1)
-m, --mode <mode>         virtual | eventloop        (eventloop lands on day 3)
    --max-clients <n>     maximum concurrent connections (default 10000)
    --appendonly <path>   enable AOF persistence at this path
    --fsync <policy>      always | everysec (default) | no
    --expiry-interval <n> active expiry cycle interval in ms (default 100)
-v, --verbose             log each connection
```

## Requirements

Java 21 or later — virtual threads and record patterns are both used, and neither is
optional to the design.
