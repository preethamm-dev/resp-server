# resp-server

A Redis-compatible server written from scratch in Java 21, with **zero runtime
dependencies** — no Netty, no client libraries, just the JDK. The official `redis-cli`
connects to it and cannot tell the difference for the commands it implements.

> **Work in progress.** Day 1 of 3 is complete: the RESP2 protocol, the keyspace, the
> string/generic/connection commands, and the virtual-thread server. Days 2 and 3 add
> the remaining data types, persistence, the event-loop server, and benchmarks against
> real Redis. This README grows with them.

## Try it

```bash
mvn -B verify
java -jar target/resp-server.jar --port 6380

# in another terminal, with the official client
redis-cli -p 6380
```

```
127.0.0.1:6380> PING
PONG
127.0.0.1:6380> SET greeting "hello world"
OK
127.0.0.1:6380> APPEND greeting "!"
(integer) 12
127.0.0.1:6380> GET greeting
"hello world!"
127.0.0.1:6380> INCR counter
(integer) 1
127.0.0.1:6380> SET session token EX 60
OK
127.0.0.1:6380> TTL session
(integer) 60
```

It also speaks the inline protocol, so a raw socket works:

```bash
printf 'PING\r\nECHO hello\r\n' | nc 127.0.0.1 6380
```

## Why this exists

Writing a Redis clone is a well-worn exercise; most stop at a toy that echoes `PONG`.
The parts of this one worth reading are the parts those skip:

- **An incremental parser.** TCP is a byte stream, not a message stream. One `read()`
  can return half a command or thirty-seven of them. The parser either consumes exactly
  one complete frame or leaves the buffer untouched — proven by a test that feeds every
  frame **one byte at a time** and requires the parser to return nothing until the final
  byte. Pipelining then falls out for free.
- **Two concurrency models, benchmarked against each other** *(day 3)*. The same command
  layer and the same keyspace sit behind both a virtual-thread-per-connection server and
  a single-threaded NIO event loop, so a benchmark isolates exactly one variable.
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
     │blocking I/O      │     │  NIO selector    │
     └────────┬────────┘     └────────┬─────────┘
              └───────────┬───────────┘
                    RespReader  ── incremental, never blocks
                          │
                   CommandRegistry  ── arity + type validation
                          │
                      Database  ── ConcurrentHashMap keyspace
                          │          atomic per-key compute
                     RedisString      (more types on day 2)
                          │
                    ExpiryManager  ── lazy today, + active on day 2
                          │
                     RespWriter → bytes
```

## Implemented so far

| Group | Commands |
|---|---|
| Connection | `PING` `ECHO` `QUIT` `SELECT` `COMMAND` `CLIENT` `INFO` |
| Generic | `DEL` `EXISTS` `TYPE` `KEYS` `DBSIZE` `FLUSHALL` `EXPIRE` `PEXPIRE` `TTL` `PTTL` `PERSIST` |
| String | `SET` (`EX` `PX` `NX` `XX` `KEEPTTL`) `GET` `GETSET` `GETDEL` `SETNX` `SETEX` `PSETEX` `MSET` `MGET` `INCR` `DECR` `INCRBY` `DECRBY` `APPEND` `STRLEN` |

## What this is not

Stated plainly, because a portfolio project that overclaims is worse than one that does
less honestly. This server has **no** replication, clustering, transactions (`MULTI`),
Lua scripting, pub/sub, blocking commands (`BLPOP`), `SCAN` cursors, RESP3, or RDB
snapshots. It supports one database rather than sixteen. `MSET` is not atomic across
keys — see below. It is a learning and benchmarking project, not a Redis replacement.

`INFO` reports `redis_version:7.0.0` as a compatibility shim, because clients gate
feature detection on that field. The adjacent `server_name` and `resp_server_version`
fields say what it actually is.

### Known limitation: multi-key atomicity

Single-key commands are atomic — the store does read-modify-write inside
`ConcurrentHashMap.compute`. Multi-key commands such as `MSET` write one key at a time,
so a concurrent reader can observe a half-applied batch. Real Redis avoids this by being
single-threaded. Fixing it here would need a global lock, which would cost far more than
it buys; the event-loop mode added on day 3 does not have the problem at all. This is a
genuine consequence of the virtual-thread design rather than an oversight.

## Testing

```bash
mvn -B verify
```

137 tests today: 107 unit, 30 integration.

| Suite | What it establishes |
|---|---|
| `RespCodecTest` | Every RESP2 type encodes and parses; malformed input is rejected rather than guessed at; encode→parse round-trips, including binary payloads and UTF-8 |
| `IncrementalParseTest` | Frames survive arbitrary fragmentation, fed one byte at a time; an incomplete parse consumes nothing; pipelined batches drain fully; partial tails are retained |
| `DatabaseTest` | Expiry semantics on a fake clock; `INCR` rejects non-canonical numbers; 100 virtual threads incrementing one key lose no updates |
| `GlobMatcherTest` | Redis glob syntax, including that a pathological pattern stays fast rather than backtracking exponentially |
| `ServerCompatibilityIT` | **Jedis** drives the real server over a socket: binary safety, 1 MB values, unicode, TTL conventions, errors that keep the connection alive, 500-command pipelines |

Expiry is tested with an injected clock rather than `Thread.sleep`, which keeps the
suite fast and removes a class of flakiness on loaded CI machines.

## Options

```
-p, --port <n>         port to listen on (default 6380)
-b, --bind <addr>      address to bind (default 127.0.0.1)
-m, --mode <mode>      virtual | eventloop        (eventloop lands on day 3)
    --max-clients <n>  maximum concurrent connections (default 10000)
-v, --verbose          log each connection
```

## Requirements

Java 21 or later — virtual threads and record patterns are both used, and neither is
optional to the design.
