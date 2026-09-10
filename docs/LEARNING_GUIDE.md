# resp-server — Learning Guide

This is not the README. The README exists to tell a visitor what the project is in two
minutes. **This document exists to teach you the system from the ground up**, so that you
can explain and defend every part of it without the code in front of you.

It is written to be read start to finish. Concepts come before code: each section explains
the underlying idea from first principles, then points at the exact class that implements
it and why it is written the way it is.

---

## Chapter 0 — How to read this

**First pass (2–3 hours).** Read chapters 1–5. Do not try to remember class names. The goal
is to be able to draw the architecture on a whiteboard and describe what happens when
someone types `SET foo bar`.

**Second pass (the bulk of the work).** Chapters 6–14, one at a time, with the code open
beside you. Each has a *prerequisites* box naming what to learn first — do that first, or
the chapter will feel like memorisation.

**Third pass.** Chapters 15–17, then the self-check questions at the end of each chapter.
If you cannot answer them with the code closed, that chapter is not done.

**Coming back in six weeks?** Read chapter 4 (architecture) and chapter 5 (request
lifecycle). Those two rebuild the mental model faster than anything else.

> A note on honesty. This guide describes what the code actually does, including the parts
> that are worse than Redis and the things that are not implemented. If you find a claim
> here you cannot verify in the source, treat the guide as wrong and the source as right.

---

## Chapter 1 — What this is and why it exists

### The problem it solves

Strictly, none. This is not a Redis replacement and never will be. What it solves is a
learning problem: **most backend engineers use network servers, databases and caches
without ever seeing inside one.** They know that Redis is fast, that it is single-threaded,
that TCP delivers bytes — as facts rather than as things they have had to make work.

### Why a Redis clone specifically

Redis is unusually good raw material:

- **The protocol is small.** RESP has five types and can be learned in an afternoon, so the
  effort goes into the engineering rather than into decoding a specification.
- **There is a reference implementation to compare against.** Real Redis is installed and
  benchmarked side by side. Almost no portfolio project has an authoritative baseline.
- **There is a third-party client to be judged by.** Jedis was written by people who have
  never seen this code and rejects anything non-conforming. That turns "I think it's
  compatible" into something testable.
- **It concentrates interesting problems.** Byte-stream framing, concurrency, durability,
  ordered data structures and expiry all appear in a system small enough to finish.

### What was actually built

A server that speaks RESP2, implements 63 commands across five data types, expires keys,
persists to disk and survives being killed — in **two interchangeable concurrency models**,
benchmarked against each other and against real Redis.

Roughly 5,300 lines of main code and 2,300 of tests, with **zero runtime dependencies**.

### The three questions it was built to answer

1. Do Java 21 virtual threads make thread-per-connection competitive with an event loop?
2. How close can a straightforward Java implementation get to a C server whose inner loops
   have been optimised for fifteen years?
3. What does it actually take to not lose data when the process is killed?

Chapter 14 and `docs/benchmarks.md` answer the first two. Chapter 11 answers the third.

---

## Chapter 2 — What "Redis-compatible" means here

This phrase is doing a lot of work, so it is worth pinning down. It is a **claim about the
wire protocol**, not about features.

### What it does mean

The official `redis-cli` connects and works. `redis-benchmark` measures it. Jedis drives it
in the test suite. For the commands implemented, the bytes on the wire are
indistinguishable from real Redis — including the parts that are easy to get subtly wrong:

- **Reply *types* match.** `GET` on a missing key returns a null bulk string (`$-1\r\n`),
  not an empty one. `LPOP` with a count returns a null *array* (`*-1\r\n`) when the key is
  missing. Clients branch on the difference.
- **Error *codes* match.** A type mismatch is `WRONGTYPE ...`, not `ERR ...`. Jedis raises
  a distinct exception on that prefix; getting it wrong breaks real client code.
- **Sentinels match.** `TTL` returns `-2` for a missing key and `-1` for one with no expiry.
- **Formatting matches.** `ZSCORE` returns `3`, not `3.0`.
- **Semantics match.** `HSET` counts fields *added*, not written. `LPUSH k a b c` produces
  `[c b a]`. Popping the last element of a list deletes the key.

### What it does not mean

No replication, clustering, transactions (`MULTI`), Lua scripting, pub/sub, blocking
commands (`BLPOP`), `SCAN` cursors, RESP3, or RDB snapshots. One database rather than
sixteen.

`INFO` reports `redis_version:7.0.0` as a deliberate shim, because clients gate feature
detection on that field. The adjacent `server_name:resp-server` and `resp_server_version`
say what it really is.

> **In an interview:** say "Redis-compatible for the commands I implemented" and then name
> what is missing before you are asked. Volunteering the boundary is what makes the claim
> credible.

---

## Chapter 3 — Repository and package structure

```
resp-server/
├── src/main/java/com/preetham/respserver/
│   ├── Main.java                    entry point: config → restore → start → shutdown hook
│   ├── config/ServerConfig.java     command-line options, concurrency mode selection
│   │
│   ├── protocol/                    bytes ↔ values. Knows nothing about commands.
│   │   ├── RespValue.java           sealed hierarchy of the 5 RESP2 types + 2 null forms
│   │   ├── RespReader.java          ★ incremental parser
│   │   ├── RespWriter.java          value → bytes
│   │   ├── ReadBuffer.java          per-connection inbound accumulation
│   │   ├── WriteBuffer.java         per-connection outbound queue (event loop)
│   │   └── ProtocolException.java   "cannot ever be valid" — distinct from "not yet"
│   │
│   ├── command/                     dispatch. Knows nothing about sockets.
│   │   ├── Command.java             functional interface: context → reply
│   │   ├── CommandSpec.java         name, arity, is-write, handler
│   │   ├── CommandRegistry.java     lookup + dispatch, converts failures to error replies
│   │   ├── CommandExecutor.java     registry + AOF gating
│   │   ├── CommandContext.java      typed argument access
│   │   ├── ClientSession.java       per-connection state (id, name, close-requested)
│   │   └── impl/                    Connection, Generic, String, List, Hash, Set, SortedSet
│   │
│   ├── store/                       the keyspace. Knows nothing about the protocol.
│   │   ├── Database.java            ★ ConcurrentHashMap keyspace, atomic per-key updates
│   │   ├── RedisObject.java         sealed: String | List | Hash | Set | SortedSet
│   │   ├── RedisString.java         immutable byte[]
│   │   ├── RedisList/Hash/Set.java  synchronised collections
│   │   ├── RedisSortedSet.java      HashMap + SkipList, kept in step
│   │   ├── SkipList.java            ★ package-private, span tracking
│   │   ├── ScoredMember.java        public (member, score) so SkipList can stay hidden
│   │   ├── Bytes.java               binary-safe value type with value semantics
│   │   ├── ValueHolder.java         value + absolute expiry deadline
│   │   ├── ExpiryManager.java       adaptive background reaper
│   │   └── GlobMatcher.java         KEYS patterns, iterative (no exponential blowup)
│   │
│   ├── persistence/
│   │   ├── AofWriter.java           append + fsync policy
│   │   ├── AofLoader.java           replay + truncation repair
│   │   └── FsyncPolicy.java         always | everysec | no
│   │
│   ├── server/                      transport. The only layer that knows about sockets.
│   │   ├── RedisServer.java         the seam: start / port / isRunning / close
│   │   ├── VirtualThreadServer.java one virtual thread per connection, blocking
│   │   ├── Connection.java          that model's read loop
│   │   ├── EventLoopServer.java     ★ one thread, NIO selector, non-blocking
│   │   └── ServerFactory.java       mode → implementation
│   │
│   └── stats/ServerStats.java       counters behind INFO
│
├── src/test/java/...                see chapter 15
├── benchmarks/                      harness, summariser, committed raw results
├── docs/                            this guide, benchmarks, ADRs, interview guide
└── scripts/crash-recovery-demo.sh   SIGKILL durability proof
```

### The rule the packages follow

**Each layer knows only about the one below it.**

- `protocol` knows bytes. It has never heard of `GET`.
- `command` knows commands. It has never heard of sockets.
- `store` knows data. It has never heard of RESP.
- `server` knows sockets. It has never heard of sorted sets.

This is why two completely different transports can share everything above them, and it is
what makes the benchmark in chapter 14 a comparison of concurrency models rather than of
two servers.

> **Prerequisites:** Java packages and visibility. Worth knowing why `SkipList` is
> package-private and `ScoredMember` is public — see ADR 0004.

---

## Chapter 4 — Architecture

```
                        redis-cli / Jedis / redis-benchmark
                                      │
                                      │  TCP
                    ┌─────────────────┴──────────────────┐
                    │        RedisServer (interface)      │
                    │   start() · port() · close()        │
                    └─────────────────┬──────────────────┘
             ┌────────────────────────┴────────────────────────┐
             │                                                  │
   ┌─────────▼──────────┐                         ┌────────────▼─────────────┐
   │ VirtualThreadServer│                         │     EventLoopServer      │
   │                    │                         │                          │
   │ accept() ──┐       │                         │  one thread, Selector    │
   │            │       │                         │                          │
   │   ┌────────▼─────┐ │                         │  select() ──┬─ ACCEPT    │
   │   │ 1 vthread    │ │                         │             ├─ READ      │
   │   │ per conn     │ │                         │             └─ WRITE     │
   │   │ blocking I/O │ │                         │                          │
   │   └──────────────┘ │                         │  per-conn Read/Write     │
   │                    │                         │  buffers, OP_WRITE       │
   │ needs locking      │                         │  no locking needed       │
   └─────────┬──────────┘                         └────────────┬─────────────┘
             └────────────────────┬───────────────────────────┘
                                  │
                          ┌───────▼────────┐
                          │   RespReader   │  incremental: whole frame or nothing
                          └───────┬────────┘
                                  │
                          ┌───────▼─────────┐
                          │ CommandExecutor │  dirty-counter gate → AOF
                          └───────┬─────────┘
                                  │
                          ┌───────▼─────────┐
                          │ CommandRegistry │  arity + type validation
                          │                 │  never throws: failures become replies
                          └───────┬─────────┘
                                  │
                          ┌───────▼─────────┐
                          │    Database     │  ConcurrentHashMap keyspace
                          │                 │  atomic per-key compute()
                          └───────┬─────────┘
            ┌──────────┬──────────┼──────────┬─────────────┐
            │          │          │          │             │
      RedisString RedisList  RedisHash  RedisSet   RedisSortedSet
       immutable  synchronised          synchronised  HashMap + SkipList
                                  │
                          ┌───────▼─────────┐
                          │  ExpiryManager  │  lazy on read + adaptive background sweep
                          └───────┬─────────┘
                                  │
                          ┌───────▼─────────┐
                          │    AofWriter    │──→ appendonly.aof ──→ AofLoader at startup
                          └───────┬─────────┘
                                  │
                          ┌───────▼─────────┐
                          │   RespWriter    │──→ bytes back to the client
                          └─────────────────┘
```

### Reading the diagram

The important structural fact is the **fork and rejoin near the top**. Two transports
diverge, then everything below is shared. That single decision (ADR 0001) is what makes the
project's central question answerable.

The second important fact is that **`CommandRegistry` never throws**. A Redis connection
survives errors — a bad command gets an error reply and the connection continues. Only a
malformed *frame*, which desynchronises the byte stream, justifies hanging up. That
distinction is drawn deliberately and is discussed in chapter 7.

> **Prerequisites:** interfaces and polymorphism; roughly what TCP is.

**Self-check:** Why do both servers sit behind one interface? What would be lost if each
had its own parser?

---

## Chapter 5 — The life of one command

Trace `SET foo bar` from keystroke to reply. This is the single most useful section in the
guide; if you can narrate it, you understand the system.

### 1. The client encodes it

`redis-cli` does **not** send the text `SET foo bar`. It sends a RESP array:

```
*3\r\n$3\r\nSET\r\n$3\r\nfoo\r\n$3\r\nbar\r\n
```

Read as: an array of 3 elements (`*3`); a 3-byte string `SET`; a 3-byte string `foo`; a
3-byte string `bar`. Every element is length-prefixed, which is what makes values
binary-safe — the payload can contain `\r\n` and the parser is unaffected because it was
told the length in advance.

### 2. The bytes arrive — possibly in pieces

The kernel hands the server whatever has turned up. That may be all 31 bytes, or the first
9, or one. **Nothing guarantees a whole command per read.** This is the fact that shapes
the entire protocol layer.

- **Virtual-thread mode:** the connection's thread is blocked in `in.read(chunk)`
  (`Connection.run`). It wakes with whatever arrived.
- **Event-loop mode:** `selector.select()` reports the socket readable, and
  `EventLoopServer.handleRead` reads into a buffer shared by all connections — safe because
  a single thread copies it out before moving on.

### 3. Accumulate

Bytes are appended to that connection's `ReadBuffer` — a growable array holding everything
received but not yet parsed. It is per-connection because a half-finished command must
survive until the rest arrives.

### 4. Parse — completely, or not at all

`RespReader.tryParse(ByteBuffer)` is called in a loop:

```java
while (true) {
    Optional<RespValue> frame = reader.tryParse(parsing);
    if (frame.isEmpty()) break;      // partial command — wait for more bytes
    execute(frame.get());
}
inbound.consume(parsing.position());  // discard exactly what was consumed
```

`tryParse` reads the type byte, then dispatches. For `*` it reads the count, then parses
that many elements recursively. **If any part is incomplete it rewinds the buffer position
to exactly where it started and returns empty.** That precision matters: consuming even one
byte of an incomplete frame would corrupt the stream in a way that only shows up under
fragmentation.

That `while` loop is also the entire implementation of **pipelining**. If a client sent 100
commands in one write, this parses and answers all 100 in one pass.

### 5. Validate and dispatch

The frame is now `ArrayReply[BulkString("SET"), BulkString("foo"), BulkString("bar")]`.

`CommandExecutor.execute` looks up the spec, notes that `SET` is a write, and samples the
keyspace's dirty counter. `CommandRegistry.dispatch`:

1. looks up `set` in a `HashMap<String, CommandSpec>`;
2. checks arity — `SET` has arity `-3`, meaning *at least* 3 arguments including the name;
3. calls the handler, catching every failure and turning it into an error reply.

### 6. Execute

`StringCommands.set` parses the optional arguments (`EX`, `PX`, `NX`, `XX`, `KEEPTTL`),
then calls `Database.set("foo", new RedisString("bar".getBytes()))`, which is
`keyspace.put(key, ValueHolder.of(value))` and a dirty increment. The reply is
`RespValue.OK`.

### 7. Persist, if it changed anything

Back in `CommandExecutor`: the command was a write and the dirty counter moved, so the
original argument list is appended to the AOF as the same RESP array the client sent. Had
this been `SET foo bar NX` against an existing key, the counter would not have moved and
nothing would be written — see chapter 11 for why that matters enormously.

### 8. Encode and send

`RespWriter.write` turns `OK` into `+OK\r\n`.

- **Virtual-thread mode:** written to a `BufferedOutputStream` and flushed once, after all
  commands in this batch — so a 100-command pipeline costs one write syscall, not 100.
- **Event-loop mode:** appended to the connection's `WriteBuffer`, then written as far as
  the kernel will accept. If the kernel takes only part of it, the remainder stays buffered
  and `OP_WRITE` is registered so the loop is told when the socket drains.

### 9. Round again

Both servers loop back to step 2.

> **Prerequisites:** what a socket is; the difference between a blocking and a non-blocking
> read; `ByteBuffer` position/limit.

**Self-check:** At which step could the command be split in half, and what stops that
breaking anything? Why is the flush deferred until after the loop?

---

## Chapter 6 — The protocol layer

> **Prerequisites:** TCP as a byte stream; `ByteBuffer` position/limit/flip; character
> encodings and why `byte[]` is not `String`.

### RESP2 in one table

| Prefix | Type | Example |
|---|---|---|
| `+` | simple string | `+OK\r\n` |
| `-` | error | `-ERR unknown command\r\n` |
| `:` | integer | `:1000\r\n` |
| `$` | bulk string | `$5\r\nhello\r\n` |
| `*` | array | `*2\r\n$3\r\nGET\r\n$3\r\nfoo\r\n` |

Plus two null forms: `$-1\r\n` (null bulk) and `*-1\r\n` (null array).

**Why simple strings and bulk strings both exist.** A simple string is terminated by CRLF,
so it cannot contain one — but it is cheap. A bulk string is length-prefixed, so it can
hold arbitrary bytes. Status replies use the cheap one; data uses the safe one.
`RespWriter.requireNoNewlines` enforces the rule: passing a value with an embedded CRLF as
a simple string throws, because it would desynchronise the client and every subsequent
reply would be off by one.

### Why values are `byte[]` and not `String`

Redis is binary safe. A value may be a JPEG, a protobuf, or contain a NUL byte. Decoding
through a charset is lossy — invalid UTF-8 becomes U+FFFD and no longer round-trips, so two
distinct values could collapse into one. `RespValue.BulkString` holds `byte[]`, and
`Bytes` provides value semantics for collection members.

`BulkString` overrides `equals`/`hashCode` because a record's generated versions compare
arrays **by identity**, which would make almost every test fail.

### The incremental parser

This is the most important class in the project. See ADR 0003.

**The contract:**

| situation | result |
|---|---|
| a complete frame is present | return it, position advanced past it |
| the frame is incomplete | return empty, **position exactly where it started** |
| the bytes cannot ever be valid | throw `ProtocolException` |

The third case is deliberately separate from the second. Conflating them is the classic bug
in hand-written network code: a command split across two reads gets rejected as malformed.

**Hostile-input guards.** A header saying `$999999999\r\n` must not cause a gigabyte
allocation, so there are limits on bulk length, array size, line length and nesting depth.
`ReadBuffer` has its own cap: a client that opens a connection and sends a huge length
prefix but no payload cannot make the server buffer forever.

**Inline commands.** A line not starting with a RESP type byte is treated as a
whitespace-separated command, so `printf 'PING\n' | nc` works. Note the asymmetry, which is
deliberate: **RESP framing requires CRLF strictly** (machine-generated, spec-mandated,
leniency would mask corruption), while **inline commands accept a bare LF** (typed by
humans and shells, which do not reliably send CR). This was found the hard way — see
chapter 17.

**Self-check:** What exactly happens if `read()` returns the first 9 bytes of a `SET`? Why
is a `ProtocolException` fatal to the connection when a wrong-arity error is not?

---

## Chapter 7 — The command layer

> **Prerequisites:** functional interfaces and method references; `Map`; checked vs
> unchecked exceptions.

### Shape

`Command` is a functional interface: `CommandContext → RespValue`. A `CommandSpec` pairs it
with a name, an arity and an is-write flag. `CommandRegistry` holds an immutable
`HashMap<String, CommandSpec>` — shared by every connection with no synchronisation,
because it is never mutated after construction.

### Arity encoding

Redis's own convention, reproduced exactly:

- **positive** — exactly this many arguments, *including the command name*. `GET key` is 2.
- **negative** — at least this many. `DEL key [key ...]` is -2.

Matching Redis here is not cosmetic: it makes the arity check and the error text behave
identically to the real server, which is what the compatibility tests assert.

### The error boundary — the important idea

**`dispatch` never throws.** Everything a client can cause becomes a reply:

| cause | reply | connection |
|---|---|---|
| unknown command | `-ERR unknown command 'X'...` | stays open |
| wrong arity | `-ERR wrong number of arguments...` | stays open |
| wrong type | `-WRONGTYPE ...` | stays open |
| non-numeric value | `-ERR value is not an integer...` | stays open |
| **malformed frame** | `-ERR Protocol error: ...` | **closed** |

The last row is the only one that closes, and the reason is precise: once the byte stream
is desynchronised there is no way to find the next command boundary. Everything else leaves
the connection perfectly usable, which is why several tests assert `PING` still works
straight after triggering an error.

There is also a final `catch (RuntimeException)` that logs and replies `ERR internal
error`. A null pointer in one rarely-used command should not take down a connection, let
alone the server.

**Self-check:** Why is `CommandException` checked while `WrongTypeException` is unchecked?
(Hint: where is the latter thrown from?)

---

## Chapter 8 — The storage layer

> **Prerequisites:** `HashMap` internals; `ConcurrentHashMap` and why it is not just a
> synchronised map; sealed interfaces and pattern matching.

### The keyspace

`ConcurrentHashMap<String, ValueHolder>`. A `ValueHolder` is `(RedisObject value, long
expireAtMillis)` — the deadline is **absolute**, so no bookkeeping is needed as time passes;
a key is expired precisely when `now >= expireAtMillis`.

### Five types, sealed

```java
sealed interface RedisObject
        permits RedisString, RedisList, RedisHash, RedisSet, RedisSortedSet
```

Sealing means the compiler knows the complete set, so a `switch` over them can be checked
for exhaustiveness — a sixth type cannot be added without the compiler pointing at every
place that must handle it.

| type | backing | why |
|---|---|---|
| `RedisString` | `byte[]`, immutable | binary safe; immutability means no locking |
| `RedisList` | `ArrayDeque<Bytes>` | O(1) at both ends — what a queue or stack needs |
| `RedisHash` | `LinkedHashMap` | stable iteration makes tests deterministic |
| `RedisSet` | `LinkedHashSet` | same |
| `RedisSortedSet` | `HashMap` + `SkipList` | chapter 9 |

**`ArrayDeque` and not `ArrayList`**: `LPUSH` on an `ArrayList` shifts every element, making
it O(n). **Not `LinkedList`**: an object header and two pointers per element. Redis uses a
quicklist to get both properties at once — a memory optimisation this project does not need.

### Empty collections do not exist

Popping the last element of a list deletes the key; `EXISTS` then replies 0. There is no
such thing as an empty list in Redis. This is handled once, centrally, in
`Database.mutateCollection` — which returns `null` from `compute` when the mutation leaves
the collection empty — rather than being re-implemented in every command.

Strings are the exception: `SET k ""` is a live key holding an empty value, which is why
`RedisString.isEmpty()` always returns false.

### Atomicity

The core technique, and the thing worth understanding properly:

```java
public long incrBy(String key, long delta) {
    long[] result = new long[1];
    keyspace.compute(key, (k, existing) -> {
        // read, parse, add, write -- all inside compute
    });
    return result[0];
}
```

`ConcurrentHashMap.compute` locks **only the bin holding that key** for the duration of the
lambda. So:

- two clients on **different keys** never contend;
- two clients on the **same key** are serialised, which is exactly the required semantics;
- the whole read-modify-write is atomic without a global lock.

Doing this as a separate `get` then `put` would lose increments under concurrency.
`DatabaseTest.incrementsAreAtomicUnderContention` runs 100 virtual threads × 1000
increments and asserts the result is exactly 100,000 — a get-then-put implementation lands
short.

### The two-argument remove

In `liveHolder`:

```java
if (holder.isExpired(now())) {
    keyspace.remove(key, holder);   // NOT remove(key)
}
```

Checking "expired?" and then removing by key alone is a race: a concurrent `SET` could
replace the value between the two steps and the fresh value would be deleted. Removing only
if the holder is still the one inspected closes the window. This is a compare-and-swap in
disguise, and it is the kind of detail that separates code that works under test from code
that works under load.

**Self-check:** Why is `RedisString` immutable but `RedisList` synchronised? What would
break if `Bytes` did not override `equals`?

---

## Chapter 9 — Sorted sets and the skip list

> **Prerequisites:** linked lists; binary search; expected vs worst-case complexity; why
> randomisation can replace balancing.

Read ADR 0004 alongside this chapter.

### Why two structures

`ZSCORE member` must be O(1) → needs a hash map. `ZRANGE` and `ZRANK` need ordering → needs
an ordered structure. Neither alone works, so `RedisSortedSet` holds both and keeps them in
step. Redis does exactly the same.

The cost is that they can diverge, and a member present in one but not the other is a
corrupt collection. Every mutation is therefore written as *remove the old pair from both,
insert the new pair into both* rather than updating in place.

### What a skip list is

A sorted linked list with express lanes. Every node gets a random height: level 1 with
probability 3/4, level 2 with 3/16, and so on.

```
  L3  H ─────────────────────────────────────► 9 ──► null
  L2  H ──────────► 3 ────────────────────────► 9 ──► null
  L1  H ──► 1 ────► 3 ──► 5 ──────────────────► 9 ──► null
  L0  H ──► 1 ────► 3 ──► 5 ──► 6 ──► 7 ──────► 9 ──► null
```

Search starts at the top and moves right while the next node is still before the target,
then drops a level. Higher levels skip exponentially more nodes, so the descent behaves
like a binary search — **with no rebalancing at all**. The "balancing" is a coin flip in
`randomLevel()`.

The bounds are *expected*, not worst case: an unlucky run of flips degrades to a linked
list. With p=0.25 and 32 levels that is vanishingly unlikely. Redis makes the same bet.

### Spans — the part that matters

Each forward pointer also stores **how many bottom-level nodes it jumps**:

```
  L2  H ──────span=3──────► 3 ──────span=4──────► 9
  L1  H ─span=1─► 1 ─span=2─► 3 ─span=2─► 5 ─span=2─► 9
```

`ZRANK` is "how many members precede this one". Accumulate the spans you traverse during
the ordinary search and the rank falls out — **no extra traversal, no separate structure**:

```java
for (int i = level - 1; i >= 0; i--) {
    while (forward[i] is still before the target) {
        traversed += x.span[i];
        x = x.forward[i];
    }
    if (x is the target) return traversed - 1;
}
```

This is the entire reason the skip list is hand-written rather than a `TreeMap`. A red-black
tree answers rank in O(n) unless every node carries a subtree size, which then has to be
repaired up the whole path on each rotation. A skip list has no rotations, so spans only
need adjusting where pointers are relinked.

### Why span maintenance is the dangerous part

On insert, the new node's spans and its predecessors' spans have to be split at the point
it lands. `rank[i]` records how far the search had travelled when it dropped from level `i`,
and the differences give the split.

**Getting this wrong does not break ordering.** Iteration still returns everything in the
right order. `ZRANGE` looks perfect. Only `ZRANK` returns wrong numbers — silently.

That is why the tests are shaped the way they are (chapter 15): a `TreeSet` oracle compared
on **rank**, plus `checkInvariants()` verifying independently that every span equals the
real distance it claims.

### One detail worth knowing

Members are compared with `Arrays.compareUnsigned`. `Arrays.compare` is **signed**, which
would place any byte above 0x7F before every ASCII character — so `é` would sort before
`a`. Redis compares unsigned, and tie-breaking between equal scores depends on it. There is
a test for exactly this.

**Self-check:** Draw a 5-element skip list and compute the rank of the 4th element by
following spans. Why can a span bug pass a test that only checks `ZRANGE`?

---

## Chapter 10 — Expiry

> **Prerequisites:** absolute vs relative time; amortised cost; why "best effort" can be
> acceptable.

Read ADR 0006 alongside.

### Two mechanisms, both necessary

**Lazy** (`Database.liveHolder`). Every read checks the deadline; a key past it is treated
as absent and deleted on the spot. This makes expiry **correct**: a key is logically gone
the instant its deadline passes, regardless of whether anything has cleaned it up.

**Active** (`ExpiryManager`). Lazy alone leaks: a key nobody reads again is never touched,
so a workload that writes a million session keys and stops reading them holds all million
forever. Something has to go looking.

### The adaptive cycle

```
every 100ms:
    repeat up to 16 times:
        sample 20 keys
        delete the expired ones
        if fewer than 25% were expired: stop
```

The feedback loop is the clever part. If a quarter of a sample has expired, a similar
fraction of the keyspace probably has, so it is worth continuing. If not, the cycle stops
after one cheap round. **The reaper costs almost nothing when there is nothing to do and
works hard exactly when there is** — without ever scanning the whole keyspace, which on a
large database would stall the server.

The 16-round cap matters too: without it, a keyspace where everything expires at once keeps
the loop running and starves real commands — trading a memory problem for a latency one.

### Where this differs from Redis

Redis samples randomly from a **dedicated dictionary of keys that have a TTL**. Two things
prevent that here: `ConcurrentHashMap` has no O(1) random selection, and maintaining a
parallel TTL index would mean touching a second structure on every `SET`, `EXPIRE`,
`PERSIST` and `DEL` — hot-path bookkeeping to speed up a background task.

So this uses a **rotating cursor** over the keyspace. Same eventual coverage with bounded
work per cycle; the cost is wasted looks at keys with no TTL. That is a genuine
disadvantage and it is stated as one.

### Why the scheduled task swallows exceptions

`scheduleWithFixedDelay` **silently cancels a task that throws**. If the cycle ever raised,
expiry would stop for the life of the process with no indication why. `runCycleSafely`
catches and logs instead. This is a small detail with a large failure mode.

**Self-check:** Why is being late acceptable for the reaper but not for the lazy check?
What breaks if the 16-round cap is removed?

---

## Chapter 11 — Persistence and crash recovery

> **Prerequisites:** the difference between `write()` and `fsync()`; what the page cache
> is; what "acknowledged" means.

Read ADR 0005 alongside.

### A write is not a save

This is the single most important idea in the chapter.

`write()` hands bytes to the **operating system's page cache** — memory. They sit there
until the kernel decides to flush, possibly tens of seconds later. So:

- If the **process** dies (crash, `kill -9`), the data survives — the kernel still holds it.
- If the **machine** loses power, it is gone.

Only `fsync()` forces data onto the physical device, and it is expensive: a real disk flush
takes milliseconds, thousands of times longer than the command that produced it.

| policy | lost to a process crash | lost to power failure | speed |
|---|---|---|---|
| `always` | nothing | nothing | slowest |
| `everysec` | nothing | ≤ 1 second | good — Redis's default |
| `no` | nothing | unbounded | fastest |

There is no correct answer, only a position on a curve — which is why it is a setting.

### What the log holds

The RESP arrays the clients sent. Replaying them rebuilds the keyspace. The protocol is
already a serialisation format, so none had to be designed, and
`redis-cli --pipe < appendonly.aof` would replay it into a real Redis.

### The dirty counter — do not skip this

Appending every write command looks harmless and is not.

`SET k v NX` against an existing key **changes nothing** — it replies nil. But replaying it
into an empty keyspace at startup **would create the key**. Recovery would produce a
different dataset from the one that was saved, and nobody would notice until they read that
key.

So `Database` counts operations that genuinely mutated state, and `CommandExecutor` samples
that counter either side of the handler, persisting only when it moved. Redis solves the
identical problem with its `server.dirty` counter.

Collection mutations mark dirty unconditionally, which over-records slightly. That is safe
because replaying an `SREM` of an absent member is a no-op. Conditional string writes cannot
take that shortcut, because their replay is *not* a no-op.

### The truncated tail

A crash mid-append leaves an incomplete frame at the end of the file. There is no way to
guess the missing bytes, so `AofLoader` replays every complete command and discards the
fragment.

**The parser makes this trivial** — for exactly the reason it was built that way on day
one. It already distinguishes "not enough bytes yet" from "malformed", and at the end of a
file the former simply means *this record was never finished*.

The fragment is then **truncated from the file**. Leaving it would corrupt everything
written afterwards, because it would be parsed as the start of the next record.

### Replay uses the live command path

`AofLoader` dispatches through the same `CommandRegistry` as a connection. A second
implementation of "apply a command" would be one more thing to keep in step, and its bugs
would only surface after a crash — the worst possible moment to find them.

### The proof

`scripts/crash-recovery-demo.sh` runs the real thing: `SIGKILL` mid-write, restart, verify.
`SIGKILL` cannot be caught — no shutdown hook, no flush, no close. Anything that survives
was already on disk. Measured output is in the README.

**Self-check:** Why does `everysec` lose nothing to a process crash? Why must the truncated
fragment be removed rather than just skipped?

---

## Chapter 12 — Concurrency model 1: virtual threads

> **Prerequisites:** OS threads and their cost; blocking I/O; what "parking" a thread means;
> the basics of the Java memory model.

### The design

Accept a socket, hand it a thread, write plain blocking code:

```java
while (!closed) {
    int read = in.read(chunk);        // blocks
    if (read < 0) break;
    inbound.append(chunk, 0, read);
    if (drainCommands(out)) out.flush();
}
```

That is `Connection.run`, and its appeal is that it reads like the problem statement.

### Why this fell out of favour, and what changed

A platform thread carries an OS thread with a stack measured in **megabytes**. Ten thousand
connections meant ten thousand threads — gigabytes of stack and a scheduler in distress.
So the industry moved to event loops.

A **virtual thread** is scheduled by the JVM, not the OS. When it blocks on I/O the JVM
unmounts it from its carrier thread and parks the continuation on the heap, costing a few
hundred bytes. The carrier goes on to serve someone else. So the simple code above becomes
viable again at connection counts that used to require an event loop.

`Executors.newVirtualThreadPerTaskExecutor()` is the whole of the change.

### The acceptor is deliberately a platform thread

It spends its life blocked in `accept()` and gains nothing from being virtual. More
importantly it must keep the server alive, and virtual threads are daemons — the JVM would
exit.

### What this model costs

**Synchronisation.** Many threads share one keyspace, so:

- the keyspace is a `ConcurrentHashMap` and every read-modify-write goes through `compute`;
- the mutable collection types are `synchronized` on themselves;
- the AOF append is serialised on a lock, because the log must record the same order the
  keyspace applied.

**None of that exists in the event-loop model.** Every line of synchronisation in `Database`
is there for this model alone.

**Multi-key atomicity is lost.** `MSET` writes one key at a time, so a concurrent reader can
observe a half-applied batch. Real Redis avoids this by being single-threaded. Fixing it
here would need a global lock, which would cost more than it buys — and the event-loop mode
does not have the problem at all.

**Self-check:** Why is a parked virtual thread cheap? Which lines of `Database` would be
deletable if only the event loop existed?

---

## Chapter 13 — Concurrency model 2: the event loop

> **Prerequisites:** non-blocking I/O; `epoll`/`kqueue` at a conceptual level; NIO
> `Selector`, `SelectionKey`, interest sets.

### The design

One thread. Instead of asking a socket for data and waiting, ask the OS **which sockets are
ready right now**, service exactly those without blocking, and go round again.

```java
while (running) {
    selector.select(200);
    for (SelectionKey key : selector.selectedKeys()) {
        if (key.isAcceptable()) accept();
        if (key.isReadable())   handleRead(key);
        if (key.isValid() && key.isWritable()) handleWrite(key);
    }
}
```

This is how Redis, nginx and Node.js are built.

### Readiness, not completion — the idea everything else follows from

A selector tells you an operation **would not block**. It does not tell you the operation
finished. Both directions need care.

**Partial reads.** One `read()` may deliver half a command. Already solved — the day-one
parser handles it unchanged. This is the payoff from ADR 0003.

**Partial writes.** This is new and it is the hard part. `write()` returns **how many bytes
the kernel accepted**, which may be fewer than offered, or zero, whenever the send buffer
is full — which happens any time the client reads more slowly than the server replies.

The loop cannot wait; blocking would stall every other connection. So the remainder goes
into the connection's `WriteBuffer` and `OP_WRITE` is registered:

```java
if (connection.outbound.isEmpty()) {
    key.interestOps(SelectionKey.OP_READ);                        // done
} else {
    key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE); // tell me when writable
}
```

### The two classic bugs

**1. Forgetting to register `OP_WRITE`.** The remainder is never sent; the client hangs
waiting for a reply that is sitting in a buffer.

**2. Forgetting to *deregister* it.** This one is worse, because everything appears to
work. A socket with room in its send buffer is writable essentially always, so leaving
`OP_WRITE` in the interest set makes `select()` return immediately, forever. The server
keeps answering correctly while burning an entire core.

**No functional test catches this.** So there is a test that measures the event loop
thread's CPU time across an idle second and asserts it stays under 250ms — a spinning
selector would consume close to 1000ms.

### Two more details that matter

**Removing the selected key.** `iterator.remove()` is mandatory. The selector does not clear
the selected set; a key left in it is reprocessed on every pass forever.

**Re-checking validity.** `handleRead` may close the connection, so `key.isValid()` is
checked before touching the key for writing.

### Bounding the output buffer

A client that stops reading but keeps sending would make the buffer grow until the server
runs out of memory — a slow-consumer denial of service. Past `DEFAULT_MAX_PENDING` (64 MB)
the connection is dropped. Redis has the same protection, called
`client-output-buffer-limit`.

### Buffer ownership

The **read** buffer is shared by every connection: one thread copies it out before moving
on, so reuse is safe and saves an allocation per read. The **write** buffers are per
connection, because unsent data must survive until that socket is ready again.

### What the single thread buys

**No synchronisation, anywhere.** The keyspace, the buffers and the session state are
touched by exactly one thread. The atomicity the virtual-thread model obtains through
`compute` and per-collection locks is free here.

That is precisely why real Redis is single-threaded, and it is the trade this project set
out to measure.

### What it costs

**Head-of-line blocking** — one slow command delays every other client — and **one core**,
no matter how many the machine has.

**Self-check:** What would you observe if `OP_WRITE` were never deregistered? Why is the
read buffer safe to share but not the write buffer?

---

## Chapter 14 — Comparing the two models

### Structural differences

| | virtual threads | event loop |
|---|---|---|
| threads | 1 per connection + acceptor | 1 total |
| I/O | blocking | non-blocking |
| partial writes | handled by the OS | **handled by you** |
| synchronisation | required throughout | none |
| multi-key atomicity | not guaranteed | guaranteed |
| parallelism | all cores | one core |
| head-of-line blocking | no | yes |
| code complexity | low | substantially higher |

### What was measured

On the machine in `docs/benchmarks.md`, three subjects, official `redis-benchmark`, warmup
discarded, 3 runs, median reported with spread:

**Unpipelined, 50 connections:** the event loop reaches **~123k ops/sec** against the
virtual-thread mode's **~54k** — roughly **2.3×**, consistently, across every command.
Against real Redis the event loop is **93–107%**, which given the noise means
*indistinguishable at this rate*.

**Pipeline depth 16:** Redis 1.4–1.6M, event loop ~920k, virtual threads ~750k. **Redis is
1.5–1.7× ahead.** This is the configuration that actually isolates per-command cost — it
amortises the syscalls away — and this server loses it. That is the honest headline.

### The interesting anomaly

The virtual-thread number is flat: ~53–55k for every command, at both 50 and 500
connections. A number that ignores both the work and the concurrency is a bottleneck in the
plumbing.

The pipelining ratios locate it:

| subject | unpipelined → P16 | gain |
|---|---:|---:|
| virtual threads | 53,619 → 761,035 | **14.2×** |
| event loop | 123,609 → 917,431 | 7.4× |
| Redis | 124,688 → 1,373,626 | 11.0× |

Virtual threads gain the **most** from pipelining. Pipelining changes exactly one thing:
commands per `read()`. So whatever limits the unpipelined case scales with **read
operations**, not with commands — pointing at the per-read cost of the blocking path: a
syscall pair per command plus parking and unparking the virtual thread each time.

**That is an inference from the data, not a proven cause.** Confirming it needs profiling —
async-profiler, or counting context switches — which has not been done. Say it that way.

### The conclusion to draw

Virtual threads make thread-per-connection **viable** — 500 connections held up fine, with
a predictable tail. They did not make it **competitive** with an event loop for this
workload on this machine.

That is a narrower and more defensible claim than the marketing, and it is the one the data
supports.

**Self-check:** Why does pipelining help the virtual-thread mode more than the event loop?
What would you profile first to confirm the hypothesis?

---

## Chapter 15 — Tests, and what each one proves

267 tests: 142 unit, 125 integration.

| suite | proves |
|---|---|
| `RespCodecTest` | Every type encodes and parses; malformed input is rejected rather than guessed at; **encode→parse round-trips** — which catches mismatches between the two halves that testing either alone would miss |
| `IncrementalParseTest` | ★ Frames survive arbitrary fragmentation, **fed one byte at a time**, with position unchanged on an incomplete parse; all 30 split points of a `SET`; pipelined batches drain fully; partial tails are retained |
| `DatabaseTest` | Expiry on a **fake clock** (fast, deterministic, no `sleep`); `INCR` rejects non-canonical numbers; **100 virtual threads × 1000 increments lose nothing** |
| `SortedSetTest` | ★ Randomised workloads compared against a `TreeSet` **oracle on rank**, not just order; span invariants verified independently; survives a full insert-then-delete cycle |
| `ExpiryManagerTest` | Unread keys are still reclaimed; live keys untouched; the cursor covers the whole keyspace; one cycle's work is bounded |
| `GlobMatcherTest` | Redis glob syntax; a pathological pattern stays fast rather than backtracking exponentially |
| `CompatibilitySuite` | ★ **Jedis** drives the real server across all five types — run **twice**, once per concurrency model |
| `EventLoopStressIT` | ★ 16 MB replies; a slow reader pinned in `OP_WRITE`; **CPU-time assertion catching a spinning selector**; 200 concurrent clients; RST disconnects; a 10,000-command pipeline checked reply by reply |
| `AofPersistenceIT` | Restart; replayed deletions; restored TTLs; truncated tail repaired; **a failed `SETNX` is not persisted** |

### Three testing ideas worth internalising

**1. Test against something you did not write.** Jedis is third-party, so it cannot share
your misunderstanding. Your parser agreeing with your writer proves only self-consistency.

**2. Use an oracle for randomised structures.** A skip list builds a different shape every
run, and a span bug is invisible to output-order assertions. Comparing rank against a
`TreeSet` catches what fixed expectations cannot.

**3. Test the thing no functional test can see.** The busy-loop test measures CPU rather
than behaviour, because the bug it hunts produces correct behaviour.

**Self-check:** Why is testing with Jedis stronger than testing with your own client? What
class of bug does the oracle catch that ordinary assertions do not?

---

## Chapter 16 — Benchmark methodology

Read `docs/benchmarks.md` in full; this is the short form of *why* it is built that way.

- **A third-party tool.** `redis-benchmark`, not a harness of mine. A load generator written
  by the server's author invites the obvious suspicion.
- **Warmup discarded.** The JVM starts interpreted and only compiles hot methods after
  thousands of invocations. A first measured run times the interpreter and the JIT.
- **Median, not best.** A best-of-N reports the luckiest scheduling the machine produced.
- **Spread printed beside every number.** Several comparisons are inside their own noise —
  Redis's LPUSH run varied ±56.5%. Anything above ±20% should be read as "about the same".
- **Identical conditions.** Same host, same loopback, same warmup, keyspace flushed between
  runs.

### What they do not prove

A laptop under WSL2 with 3.7 GB of RAM. No network. `redis-benchmark` itself competes for
the same 8 cores at high rates. Short runs from an empty keyspace. One machine, one JDK, one
day. **Not** that this server is as fast as Redis — the pipelined configuration shows Redis
1.5–1.7× ahead.

Being able to say all of that unprompted is worth more in an interview than any number in
the table.

---

## Chapter 17 — Limitations, and the bugs that were actually hit

### Known limitations

- **`MSET` is not atomic across keys** under the virtual-thread model.
- **The AOF is never rewritten** — it grows with write count, not data size. This is the
  largest missing piece of the persistence story.
- **AOF appends serialise on one lock**, a real bottleneck under many writers.
- **Active expiry uses a cursor, not random sampling** — wasted looks at non-volatile keys.
- **No replication, clustering, transactions, scripting, pub/sub, `SCAN`, RESP3, RDB.**
- **One database**, not sixteen.

### Bugs actually encountered

These are worth knowing precisely, because "tell me about a bug you found" is a standard
question and a real answer beats an invented one.

**1. `INCR` accepted `"+1"`.** `Long.parseLong` is more permissive than Redis and accepts a
leading `+`. So `INCR` on a value of `"+1"` succeeded and silently rewrote the client's
stored text to `"2"`. Found by a test enumerating non-canonical numbers. Fixed with explicit
validation in `RedisString.asLong`.

**2. Missing `SETNX`/`SETEX`/`PSETEX`.** Jedis's `setnx()` and `setex()` send the *legacy
standalone* commands rather than `SET` with options. The server was unusable from a real
client despite `SET` being correct. **Only a third-party client could have found this** —
and it is the clearest justification for testing with Jedis.

**3. Inline commands required CRLF.** Found while building the crash demo, when a 5,000-key
bulk load silently loaded **zero** keys. `echo` emits a bare `\n`; the parser demanded
`\r\n`, so the frame never completed and the server just waited. The fix draws a deliberate
distinction: RESP framing stays strict, inline commands accept bare LF.

**4. A package-private type leaked into a public API.** `SkipList.Entry` was appearing in
`RedisSortedSet`'s public signature. Fixed by introducing `ScoredMember` rather than making
the skip list public — which structure backs a sorted set should stay an implementation
detail.

**5. A shadowed exception class.** While writing the event loop, a nested
`ClosedSelectorException` shadowed `java.nio.channels.ClosedSelectorException`, so the real
one thrown by `select()` would never have been caught. Caught by reading the code before
compiling.

---

## Chapter 18 — What to learn, in order

Honest estimates. Do not compress the first two.

| # | Topic | Hours | Why it comes here |
|---|---|---:|---|
| 1 | **Java concurrency fundamentals** — threads, `synchronized`, `ReentrantLock`, the memory model, `happens-before`, `volatile`, concurrent collections | 12–16 | Underpins chapters 8, 12, 13. The most-asked area at your level and the least fakeable |
| 2 | **Virtual threads** — carriers, mounting/unmounting, parking, pinning | 5–6 | Chapter 12 |
| 3 | **TCP framing** — byte stream vs messages, partial reads and writes | 5–6 | Chapters 5, 6 — the conceptual core |
| 4 | **NIO and event loops** — `Selector`, `SelectionKey`, interest sets, `epoll` | 8–10 | Chapter 13, the hardest chapter |
| 5 | **Benchmark methodology** — percentiles, warmup, coordinated omission, noise | 4–5 | Chapter 16. What makes your numbers survive scrutiny |
| 6 | **Skip lists** — probabilistic structures, spans | 4–5 | Chapter 9 |
| 7 | **Durability** — `write` vs `fsync`, the page cache | 3–4 | Chapter 11 |
| 8 | **Collections internals** — `HashMap`, `ConcurrentHashMap`, `ArrayDeque` | 4–5 | Chapter 8 |
| 9 | **Reading this codebase closely** | 12–15 | Everything |
| 10 | **Rehearsing out loud** | 10–12 | `docs/INTERVIEW_GUIDE.md` |

**Total: roughly 65–85 hours** for this project, on top of general Java fundamentals.

Start with **chapter 5** (the request lifecycle) and **topic 3** (TCP framing). Between
them they unlock more of the system than anything else.

---

## Chapter 19 — What you should be able to explain with the code closed

Work through these. If any is shaky, that chapter is not finished.

1. Trace `SET foo bar` from `redis-cli` to `+OK`, naming each layer.
2. Why must the parser be incremental? What breaks if it is not?
3. Why does `dispatch` never throw, and what is the one case that closes a connection?
4. How is `INCR` made atomic without a global lock?
5. Why does a sorted set need two structures?
6. What is a span, and why is `ZRANK` the reason the skip list is hand-written?
7. Why are lazy and active expiry both necessary?
8. Why does `everysec` lose nothing to a process crash but up to a second to a power cut?
9. Why must a failed `SET ... NX` not be written to the AOF?
10. What is a partial write, and what are the two `OP_WRITE` bugs?
11. What does the single-threaded model buy, and what does it cost?
12. What did the benchmarks show, and what do they *not* prove?
13. Name three real bugs you hit and how each was found.
14. What would you do differently with three more days?

### And what you must not claim

- That this is production-ready, or a Redis replacement.
- That it is as fast as Redis. The pipelined benchmark shows Redis 1.5–1.7× ahead.
- Any number you have not personally measured on your own machine.
- Deep expertise in Netty, Loom internals, or Redis's C source.
- That you implemented replication, clustering, transactions, scripting or pub/sub.

An honest *"I didn't implement that, here's why, and here's what I'd need to learn"* scores
better than a bluff that collapses under one follow-up.
