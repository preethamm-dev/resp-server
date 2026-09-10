# resp-server — Interview Guide

This is not a list of answers to memorise. Reciting a prepared paragraph is obvious within
two sentences, and the follow-up question exposes it immediately.

What this guide teaches is the **reasoning chain** behind each answer, so that when an
interviewer pushes in an unexpected direction you can keep going. Every section is built as
a question that branches into the follow-ups a real interviewer would actually ask.

Each node has the same shape:

```
▸ What they're testing      why the question exists at all
▸ The simple answer         30 seconds, plain language
▸ What you should say       your actual answer, grounded in your code
▸ If they push further      the deeper layer
▸ Where this lives          the file to point at
▸ Common mistakes           how people get this wrong
```

Work through it out loud. Reading it silently does not build the muscle you need.

---

## The 60-second pitch

Rehearse this until it is comfortable. It is the first thing you will say about the project
and it sets up every question that follows.

> "It's a Redis-compatible server written from scratch in Java 21 — no Netty, no libraries,
> just the JDK. The official `redis-cli` connects to it and can't tell the difference for
> the 63 commands I implemented, across all five data types.
>
> The reason I built it was a specific question: Java 21 shipped virtual threads, and the
> claim is that thread-per-connection is viable again. So I implemented the server twice —
> once with a virtual thread per connection, once as a single-threaded NIO event loop —
> behind the same interface, sharing the same command layer and keyspace. Then I
> benchmarked both against real Redis with the official `redis-benchmark`.
>
> The answer was that the event loop is about 2.3× faster unpipelined, and under pipelining
> Redis is still 1.5–1.7× ahead of my best mode. So virtual threads made
> thread-per-connection *viable*, not *competitive* — which is a narrower claim than the
> marketing makes.
>
> It also persists to disk and survives `kill -9`, which I prove with a script rather than
> assert."

**Why this works:** it names a question rather than a feature list, it gives a number, and
it volunteers the result that is *unflattering*. That last part buys more credibility than
anything else you can say.

---

# Level 1 — Beginner

Fundamentals the project touches. Expect these in a screen or from a non-specialist.

## 1.1 "What is Redis, and why is it fast?"

**▸ Testing:** whether you understand it beyond "it's a cache".

**▸ Simple answer:** An in-memory data structure server. Fast because the data is in RAM
rather than on disk, and because the operations are simple — mostly hash lookups.

**▸ You should say:** *"It's an in-memory key-value store, but 'key-value' undersells it —
values are typed data structures: strings, lists, hashes, sets and sorted sets, each with
its own commands. The speed comes from three things: everything is in memory; the
operations are simple, mostly O(1) hash lookups; and it's single-threaded, so there's no
locking at all. That last one surprises people — it's usually presented as a limitation,
but avoiding synchronisation is a large part of why it's quick."*

**▸ If they push:** for a workload of tiny in-memory operations, the cost is dominated by
syscalls and the network stack rather than by the data work. My own benchmarks show that
directly — unpipelined, my Java event loop is within measurement noise of real Redis,
because both are spending their time in `read` and `write`. Under pipelining, which
amortises the syscalls away, Redis pulls clearly ahead.

**▸ Common mistakes:** calling it "just a cache"; saying single-threaded is purely a
weakness.

---

## 1.2 "What does your project actually do?"

**▸ Testing:** can you scope your own work honestly.

**▸ You should say:** *"It speaks the Redis wire protocol — RESP2 — so real Redis clients
work against it unmodified. 63 commands, five data types, key expiry with TTLs, and
append-only-file persistence that survives the process being killed. What it does not have
is replication, clustering, transactions, Lua scripting or pub/sub. It's a learning and
benchmarking project, not a Redis replacement, and the README says exactly that."*

**▸ Common mistakes:** overstating scope. Naming the boundary before you are asked is what
makes the rest credible.

---

## 1.3 "Why write it in Java instead of using Redis?"

**▸ Testing:** whether you know the difference between a product and an exercise.

**▸ You should say:** *"There's no reason to use it over Redis and I wouldn't. I built it
because I use network servers, caches and databases every day without ever having seen
inside one. Writing the protocol parser, the concurrency model and the persistence layer by
hand turns things I'd read about — TCP being a byte stream, `fsync` being different from
`write` — into things I've had to make work."*

**▸ Common mistakes:** inventing a business justification. "To learn" is the honest answer
and it lands better.

---

## 1.4 "What is TCP, and why does it matter here?"

**▸ Testing:** the single most important concept in the project.

**▸ Simple answer:** TCP gives you an ordered, reliable **stream of bytes**. It does not
give you messages.

**▸ You should say:** *"TCP guarantees the bytes arrive in order and without loss. It
guarantees nothing about how they're grouped. If a client sends `SET foo bar`, one `read()`
might return all 31 bytes, or the first 9, or one — and it might return two and a half
commands. So any protocol on top of TCP has to define its own framing, and the server has
to handle a command arriving in pieces. That single fact shapes the entire protocol layer
of my project."*

**▸ If they push:** which is why my parser is incremental — it either consumes exactly one
complete frame or leaves the buffer untouched. I test it by feeding every frame **one byte
at a time** and requiring it to return nothing until the final byte.

**▸ Where:** `RespReader.java`, `IncrementalParseTest.java`

**▸ Common mistakes:** saying TCP delivers messages, or that "packets" map to reads.

---

## 1.5 "How do you test something like this?"

**▸ Testing:** testing maturity.

**▸ You should say:** *"Three layers. Unit tests for the codec and the data structures.
Integration tests that start a real server on an ephemeral port and drive it over a socket
with **Jedis** — a third-party Redis client. And a script that kills the server with
`SIGKILL` and checks the data survived.*

*The Jedis part is the one I'd highlight. Every other test checks the server against my own
understanding of the protocol — if that understanding is wrong, my tests are wrong the same
way and agree with each other perfectly. Jedis was written by people who've never seen my
code, against the real specification. That's what makes the compatibility claim mean
something."*

**▸ If they push:** it found a real bug. Jedis's `setnx()` sends the legacy standalone
`SETNX` command rather than `SET` with the `NX` option. My `SET` was correct, but the
server was unusable from a real client. No test I wrote myself would have found that.

---

# Level 2 — Intermediate

## 2.1 "Walk me through what happens when a client sends `SET foo bar`."

**▸ Testing:** whether you understand your own system end to end. This is the most likely
question you will get, and the most revealing.

**▸ You should say:**

1. *"The client encodes it as a RESP array: `*3\r\n$3\r\nSET\r\n$3\r\nfoo\r\n$3\r\nbar\r\n`.
   Every element is length-prefixed, which is what makes values binary-safe."*
2. *"Bytes arrive — possibly in pieces. In virtual-thread mode a thread is blocked in
   `read()`. In event-loop mode the selector reports the socket readable."*
3. *"They're appended to that connection's `ReadBuffer`."*
4. *"`RespReader.tryParse` is called in a loop. It either returns a complete frame or says
   'not yet' and rewinds. That loop is also the whole of pipelining."*
5. *"`CommandRegistry` looks up `set`, checks arity, calls the handler."*
6. *"The handler parses the options and calls `Database.set`, which is a
   `ConcurrentHashMap.put` plus a dirty-counter increment."*
7. *"If the dirty counter moved, the command is appended to the AOF."*
8. *"`RespWriter` encodes `+OK\r\n` and it's written back — buffered, flushed once per
   batch."*

**▸ If they push on any step:** each has its own chain later in this guide.

**▸ Common mistakes:** skipping the framing step; not knowing where the flush happens.

---

## 2.2 "Why does your parser have to be incremental?"

**▸ Testing:** whether you've written real protocol code.

**▸ Simple answer:** because a command can arrive in pieces, and the parser must not
mistake "incomplete" for "invalid".

**▸ You should say:** *"Because one `read()` doesn't equal one command. My `tryParse` either
consumes exactly one complete frame and advances the buffer, or leaves the position exactly
where it started and returns empty. Only bytes that can never be valid throw.*

*Keeping 'not enough bytes yet' separate from 'malformed' is the whole thing. Conflating
them is the classic bug: a command split across two reads gets rejected as a protocol
error, and it only shows up under load or across a slow link."*

**▸ If they push — "why build that on day one?":** three things fell out of it for free.
Pipelining: the read loop drains every buffered command, so 100 commands in one write cost
one flush. The event loop on day 3: a non-blocking read *cannot* wait for the rest of a
frame, so an incremental parser isn't an optimisation there, it's a precondition — had I
deferred it I'd have had to rewrite the parser and both servers. And AOF crash recovery: a
file whose last record was cut off by a crash is exactly "not enough bytes yet" at the end
of the buffer, so the loader needed no special parsing at all.

**▸ Where:** `RespReader.java`, ADR 0003

**▸ Common mistakes:** describing it as an optimisation rather than a correctness
requirement.

---

## 2.3 "How did you make `INCR` atomic?"

**▸ Testing:** practical concurrency, not textbook recital.

**▸ Simple answer:** the whole read-modify-write runs inside
`ConcurrentHashMap.compute`, which locks just that key's bin.

**▸ You should say:** *"`INCR` is read, parse, add, write. If you do that as a separate
`get` and `put`, two clients interleave and you lose increments. I do the whole sequence
inside `compute`, which holds the lock on that one bin for the duration. So two clients on
different keys never contend, and two on the same key are serialised — which is exactly the
semantics you want.*

*There's a test that runs 100 virtual threads doing 1000 increments each and asserts the
result is exactly 100,000. A get-then-put implementation lands short."*

**▸ If they push — "why not `synchronized`?":** a global lock would serialise the entire
server and throw away the point of having many threads. `compute` gives per-key granularity
for free.

**▸ If they push — "what about multi-key?":** it isn't atomic, and I document that. `MSET`
writes one key at a time, so a concurrent reader can see a half-applied batch. Real Redis
avoids it by being single-threaded. Making it atomic here would need a global lock, which
costs more than it buys — and my event-loop mode doesn't have the problem at all.

**▸ Where:** `Database.incrBy`, `DatabaseTest.Concurrency`

**▸ Common mistakes:** claiming `ConcurrentHashMap` makes compound operations atomic by
itself. It does not — `get` then `put` is still a race.

---

## 2.4 "What's the difference between `write()` and `fsync()`?"

**▸ Testing:** whether you understand durability or just use the word.

**▸ Simple answer:** `write()` puts bytes in the kernel's page cache — memory. `fsync()`
forces them onto the physical device.

**▸ You should say:** *"`write` only hands the bytes to the OS. They sit in the page cache
until the kernel decides to flush, which can be tens of seconds. So if the **process** dies
the data survives, because the kernel still has it — but if the **machine** loses power,
it's gone. Only `fsync` forces it to the device, and it's expensive: a real disk flush is
milliseconds, thousands of times longer than the command that produced it.*

*That's why my AOF has three policies. `always` fsyncs every write — nothing acknowledged
is ever lost, and throughput collapses to the disk's flush rate. `everysec` fsyncs in the
background — at most a second lost to a power cut, nothing lost to a process crash.
`no` lets the kernel decide. There's no correct answer, only a position on a curve, which
is why it's a setting."*

**▸ Where:** `FsyncPolicy.java`, `AofWriter.java`

**▸ Common mistakes:** saying a successful `write()` means the data is safe.

---

## 2.5 "Why does a sorted set need two data structures?"

**▸ Testing:** whether you chose the structure or copied it.

**▸ You should say:** *"Because the operations pull in opposite directions. `ZSCORE member`
has to be O(1) — it's one of the most frequent commands — which needs a hash map.
`ZRANGE` and `ZRANK` need ordering, which a hash map can't give you at all. So I hold both
a `HashMap` from member to score and a skip list ordered by score, and keep them in step.
That's exactly how Redis implements it.*

*The cost is that they can diverge, and a member in one but not the other is a corrupt
collection. So every mutation is written as 'remove the old pair from both, insert the new
pair into both' rather than updating in place, and I have a `checkInvariants` that verifies
they agree."*

**▸ Where:** `RedisSortedSet.java`, ADR 0004

---

# Level 3 — Advanced

## 3.1 "Why a skip list rather than a `TreeMap`?"

**▸ Testing:** whether you can justify a hand-written structure over a library one. There is
a wrong answer here — "for fun" — and a right one.

**▸ Simple answer:** `ZRANK`. A skip list answers rank in O(log n) for free; a balanced tree
doesn't.

**▸ You should say:** *"`ZRANK` is 'how many members precede this one'. A red-black tree
answers that in O(n) — you have to count — unless every node also stores its subtree size,
and then that size has to be repaired up the entire path on every rotation.*

*A skip list stores a **span** on each forward pointer: how many bottom-level nodes that
pointer jumps. Rank falls out of the ordinary search — you accumulate the spans you
traverse. No extra traversal, no separate structure, and crucially no rotations to keep
consistent, because a skip list never rotates. Insert and delete only relink forward
pointers."*

**▸ If they push — "how does a skip list work?":** it's a sorted linked list with express
lanes. Each node gets a random height — level 1 with probability 3/4, level 2 with 3/16,
and so on. Higher levels skip exponentially more nodes, so searching descends like a binary
search with no rebalancing at all. The "balancing" is a coin flip.

**▸ If they push — "worst case?":** O(n). The bounds are *expected*, not worst case — an
unlucky run of flips degrades to a linked list. With p=0.25 and 32 levels that's vanishingly
unlikely, and Redis makes the same bet.

**▸ If they push — "how do you know your spans are right?":** this is the good part. **A
span bug is silent.** Ordering stays correct, iteration returns everything, `ZRANGE` looks
perfect — and only `ZRANK` returns wrong numbers. A test that checks output order would
never catch it. So I run randomised workloads against a `TreeSet` oracle and compare
**rank**, not just order, plus an invariant check that verifies every span equals the real
distance it claims to jump.

**▸ Where:** `SkipList.java`, `SortedSetTest.java`, ADR 0004

**▸ Common mistakes:** claiming skip lists are faster than trees in general. They are not —
the argument is about rank queries and implementation simplicity.

---

## 3.2 "Why did you build an event loop?" ★ the central chain

This is the chain most likely to go deep. Follow it all the way.

**▸ Testing:** whether you chose it or copied it.

**▸ You should say:** *"To answer the question the project exists for. Java 21 shipped
virtual threads and the claim is that thread-per-connection is viable again. That's a claim
you can measure, so I implemented both models behind the same `RedisServer` interface,
sharing the same command layer and keyspace, and benchmarked them. The event loop is the
control — it's what Redis actually does."*

---

### ↳ "What problem does an event loop solve?"

**▸ You should say:** *"Before non-blocking I/O, one connection meant one OS thread with a
stack measured in megabytes. Ten thousand connections meant ten thousand threads, which was
gigabytes of stack and a scheduler in distress. The event loop inverts it: never block, ask
the OS which sockets are ready, handle those. You trade a simple programming model for a
much cheaper one — a connection costs a socket and a couple of buffers instead of a
thread."*

**▸ Common mistakes:** saying it's "faster". It isn't inherently faster; it's cheaper per
connection. (Though in my measurements it *was* also faster, for a reason worth explaining
— see 3.4.)

---

### ↳ "How does NIO actually work?"

**▸ You should say:** *"You register channels with a `Selector` and declare interest —
`OP_ACCEPT`, `OP_READ`, `OP_WRITE`. `select()` blocks until at least one channel is ready,
then hands back the ready set. You loop over it, do the work without blocking, and go
round. The key inversion is that you never ask a socket 'give me data and wait' — you ask
the OS 'which of these has data right now.'"*

**▸ If they push:** underneath it's `epoll` on Linux, `kqueue` on BSD, IOCP on Windows.
Important: it's **readiness-based**, not completion-based. The OS tells you a read *would
not block* — not that data has been delivered somewhere. That distinction is why you still
handle partial reads and writes yourself.

**▸ Common mistakes:** calling NIO "asynchronous". It's non-blocking, which is different.

---

### ↳ "What happens with partial reads?"

**▸ You should say:** *"Already solved, because my parser was incremental from day one. One
`read()` can give me half a command or two and a half. Each connection has an accumulation
buffer; I append whatever arrived and parse as many complete commands as I can. If a frame
is incomplete the parser returns empty and leaves the buffer position exactly where it
started."*

**▸ Where:** `ReadBuffer.java`, `EventLoopServer.handleRead`

---

### ↳ "And partial writes?" ★ the differentiating question

**▸ Testing:** almost nobody thinks about this direction. Answering it well is a strong
signal.

**▸ You should say:** *"This is the harder half and it's new to the event loop. `write()`
returns **how many bytes the kernel accepted**, which can be fewer than you offered, or
zero — whenever the send buffer is full. That happens any time the client reads more slowly
than the server replies, which is normal, not exceptional.*

*In blocking mode you just wait. In the event loop I can't — blocking would stall every
other connection. So the remainder goes into that connection's `WriteBuffer`, I register
`OP_WRITE`, and the selector tells me when the socket drains. Then I finish it and
**deregister** `OP_WRITE`."*

**▸ If they push — "why deregister?":** because that's the classic bug and it's nasty. A
socket with room in its send buffer is writable essentially always. If you leave `OP_WRITE`
in the interest set, `select()` returns immediately forever — the server keeps answering
correctly while burning an entire core. **No functional test catches it.** So I wrote one
that measures the event loop thread's CPU time across an idle second and asserts it stays
under 250ms; a spinning selector would be near 1000ms.

**▸ If they push — "what about a client that never reads?":** that's a slow-consumer denial
of service — the buffer grows until you run out of memory. I cap it at 64 MB and drop the
connection. Redis has the same protection under the name `client-output-buffer-limit`.

**▸ Where:** `WriteBuffer.java`, `EventLoopServer.flush`,
`EventLoopStressIT.doesNotBusyLoopOnceTheWriteBufferEmpties`

---

### ↳ "Why not just use Netty?"

**▸ You should say:** *"For anything real I would. It's mature, it's fast, it has zero-copy
buffers and a proper TLS story, and it's already met every edge case I met. I wrote it by
hand because Netty's whole value is that you don't have to think about selectors, partial
writes and `OP_WRITE` registration — and thinking about exactly those was the point. If I'd
imported it, the project would have been a configuration exercise."*

**▸ Common mistakes:** implying you'd hand-roll NIO in production. That reads as poor
judgment. Say the opposite explicitly.

---

### ↳ "Then why not drop the event loop and use only virtual threads?"

**▸ You should say:** *"That's the question I had numbers for. The event loop was about
**2.3× faster** unpipelined — around 123k ops/sec against 54k — and that held across every
command and both 50 and 500 connections.*

*But the interesting part isn't the ratio, it's what each model costs. Virtual threads give
you straightforward blocking code and real parallelism, and they need synchronisation
everywhere: a concurrent keyspace, per-collection locks, a lock on the AOF append. The
event loop has one thread and needs none of that. Every line of synchronisation in my
`Database` exists for the virtual-thread model alone.*

*So virtual threads don't remove the problem — they relocate it, from thread count to
shared-state coordination. Which is also why Redis stayed single-threaded."*

**▸ Common mistakes:** claiming virtual threads make event loops obsolete. Too strong, and
easy to dismantle.

---

### ↳ "When would your choice stop being appropriate?"

**▸ Testing:** whether you know your own limits. This is the mark of a senior answer.

**▸ You should say:** *"Several points. One — if any command became genuinely CPU-heavy,
the single-threaded event loop would head-of-line block every other client; that's the
price of one thread. Two — the event loop uses one core no matter how many the machine has,
so past a certain rate the answer is to shard, not to optimise. Three — my virtual-thread
version does per-key locking, so a hot key becomes a contention point that single-threaded
Redis simply doesn't have. And four — if I added blocking commands like `BLPOP`, both
designs would need rethinking, which is partly why I left them out."*

---

## 3.3 "What do your benchmark numbers actually prove?"

**▸ Testing:** intellectual honesty. Interviewers probe this hard, because inflated
benchmark claims are common.

**▸ You should say:** *"Less than they look like. Let me give you the numbers and then the
caveats.*

*Unpipelined at 50 connections, my event loop is ~123k ops/sec, virtual threads ~54k, real
Redis ~125k. At pipeline depth 16: Redis 1.4–1.6M, event loop ~920k, virtual threads ~750k.*

*The honest reading is that **unpipelined I'm within noise of Redis, and pipelined Redis is
1.5–1.7× ahead**. And the pipelined figure is the meaningful one, because pipelining
amortises the syscalls away and leaves per-command cost — parsing, hashing, allocation.
That's where C with fifteen years of optimisation beats straightforward Java, and it
should."*

**▸ If they push — "so you're as fast as Redis unpipelined?":** *"No — I'd say we're both
bottlenecked on the same thing at that rate. Unpipelined, both servers spend most of their
time in `read` and `write`; the actual data work disappears underneath. It's not that my
implementation is as good, it's that the syscall path dominates."*

**▸ If they push — "how do you know the numbers are sound?":** I used the official
`redis-benchmark` rather than writing my own load generator — a harness written by the
server's author invites the obvious suspicion. I discard a warmup pass, because the JVM
starts interpreted and a first measured run times the interpreter and the JIT. I report the
**median of three runs, not the best** — a best-of-N reports the luckiest scheduling. And I
print the spread beside every number, because several of my comparisons are inside their own
noise; Redis's LPUSH run varied by ±56%.

**▸ If they push — "what don't they prove?":** it's a laptop under WSL2 with 3.7 GB of RAM.
Loopback only, no network. `redis-benchmark` itself competes for the same 8 cores at high
rates. Short runs from an empty keyspace. One machine, one JDK, one day. Nothing about
sustained load, large values, or millions of keys.

**▸ Common mistakes:** quoting the flattering configuration and omitting the other.
Volunteering the pipelined result is what makes the rest believable.

---

## 3.4 "Your virtual-thread mode plateaus at 55k. Why?"

**▸ Testing:** whether you investigated your own anomaly or ignored it. Excellent question
to be ready for, because it shows you read your own data.

**▸ You should say:** *"I noticed that too, and it's the most suspicious thing in the
results. It's ~53–55k for every command, at both 50 and 500 connections. A number that
ignores both the work being done and the concurrency offered isn't a command cost — it's a
bottleneck in the plumbing.*

*The pipelining figures locate it. Going from unpipelined to depth 16, virtual threads gain
**14.2×**, the event loop 7.4×, Redis 11×. Virtual threads gain the *most*. Pipelining
changes exactly one thing: how many commands arrive per `read()`. So whatever limits the
unpipelined case scales with the number of **read operations**, not commands — which points
at the per-read cost of the blocking path: a syscall pair per command, plus parking and
unparking the virtual thread each time a read finds nothing ready."*

**▸ If they push — "did you confirm it?":** *"No, and I want to be clear about that. It's an
inference the data supports, not a proven cause. Confirming it means profiling both paths —
async-profiler, or counting context switches with `perf`. I ran out of time. If I picked
this up again that's the first thing I'd do."*

**▸ Common mistakes:** presenting a hypothesis as a finding. Saying "I inferred this, I
didn't confirm it" is a *strength*.

---

## 3.5 "Tell me about a bug you found." ★

**▸ Testing:** whether you have real debugging experience. Have three ready.

**The one that shows the value of third-party testing:**

*"Jedis's `setnx()` and `setex()` send the **legacy standalone** `SETNX` and `SETEX`
commands, not `SET` with options. My `SET` handled `NX` and `EX` perfectly, so all my own
tests passed — but the server was unusable from a real client. Only testing against an
independent implementation could have found that."*

**The one that shows care about correctness:**

*"`INCR` accepted `"+1"`. `Long.parseLong` is more permissive than Redis — it accepts a
leading `+`. So `INCR` on a value of `"+1"` succeeded and silently rewrote the client's
stored data to `"2"`. Found by a test enumerating non-canonical numbers: `" 1"`, `"1.0"`,
`"007"`, `"+1"`. Redis rejects all of those because accepting them would make `INCR` lossy —
the stored text wouldn't round-trip."*

**The one that shows a real debugging session:**

*"Building the crash-recovery demo, a 5,000-key bulk load silently loaded **zero** keys. No
error, no timeout, the server just sat there. `echo` emits a bare `\n`, and my inline-command
parser demanded `\r\n` — so the frame never completed and the server correctly waited
forever for a CR that was never coming. Real Redis splits inline input on LF and strips an
optional CR.*

*The fix drew a distinction I now think is the right one: **RESP framing stays strict**,
because it's machine-generated and the spec mandates CRLF, so leniency there would mask real
corruption. **Inline commands are forgiving**, because they're typed by humans and produced
by shells, which don't reliably send CR."*

**▸ Common mistakes:** an invented or trivial bug. These three are real, they are in the
git history, and each demonstrates something different.

---

# Level 4 — Deep follow-ups specific to this project

## 4.1 "Why does your dispatcher never throw?"

**▸ You should say:** *"Because a Redis connection survives errors. Send `GET` with no
arguments and you get an error reply and the connection stays open for the next command. So
there's a boundary between 'this command failed' and 'this connection is broken', and I draw
it deliberately.*

*Anything a client can cause — bad arity, unknown command, wrong type, a non-numeric value —
becomes a reply. The only thing that closes the connection is a malformed **frame**, because
once the byte stream is desynchronised there's no way to find the next command boundary.
That's exactly why `ProtocolException` is a separate type from `CommandException`.*

*There's also a final `catch (RuntimeException)` that logs and replies 'ERR internal error'.
A null pointer in one rarely-used command shouldn't take down a connection, let alone the
server."*

**▸ Where:** `CommandRegistry.dispatch`

---

## 4.2 "Why must a failed `SET ... NX` not be written to the AOF?" ★

**▸ Testing:** whether you thought about replay correctness. This is a subtle one and
answering it well is memorable.

**▸ You should say:** *"Because it would change the data. `SET k v NX` against an existing
key does nothing — it replies nil. But replaying it into an **empty** keyspace at startup
would **create** the key. So the dataset after recovery would differ from the one that was
saved, and nobody would notice until they read that key.*

*So I keep a counter on the keyspace of operations that genuinely mutated state, and the
executor samples it either side of the handler. A command is persisted only if the counter
moved. Redis solves the identical problem the same way — its `server.dirty` counter."*

**▸ If they push — "is that always necessary?":** no, and I'm coarser where it's safe.
Collection mutations mark dirty unconditionally, because replaying an `SREM` of an absent
member or an `LPOP` of a missing key is a no-op. It's only the conditional writes where
replay isn't idempotent.

**▸ Where:** `CommandExecutor.execute`, `Database.dirtyCount`,
`AofPersistenceIT.failedConditionalWritesAreNotPersisted`

---

## 4.3 "What happens if the process dies mid-write?"

**▸ You should say:** *"The file ends with an incomplete RESP frame. There's no way to guess
the missing bytes, so the loader replays every complete command and discards the fragment.*

*My parser made that almost free, for the reason it was built that way on day one — it
already distinguishes 'not enough bytes yet' from 'malformed', and at the end of a file the
former just means 'this record was never finished'.*

*Then I **truncate** the file to the last complete frame. That part matters: leaving the
fragment would corrupt everything written afterwards, because it'd be parsed as the start of
the next record.*

*And I prove it rather than assert it — there's a script that `SIGKILL`s the server
mid-write, restarts it and verifies. `SIGKILL` can't be caught, so no shutdown hook runs and
no buffer is flushed. Whatever survives was already on disk."*

**▸ Where:** `AofLoader.java`, `scripts/crash-recovery-demo.sh`

---

## 4.4 "Why is your read buffer shared but your write buffers per-connection?"

**▸ Testing:** whether you understand your own lifetimes. A nice, specific question.

**▸ You should say:** *"Lifetime. In the event loop there's one thread, and it copies the
read buffer's contents into the connection's accumulation buffer before it moves on — so the
data doesn't need to outlive the iteration and one shared buffer is safe, which saves an
allocation per read.*

*Unsent write data is different: it has to survive until that specific socket becomes
writable again, which might be many loop iterations later. So it has to be per connection."*

---

## 4.5 "What would you do with three more days?"

**▸ Testing:** whether you know what's missing.

**▸ You should say:** *"In order. First, **AOF rewrite** — right now the log grows with the
number of writes rather than the size of the data, so a counter incremented a million times
is one key and a million records. That's the biggest gap in the persistence story.*

*Second, **profile the virtual-thread bottleneck** properly instead of leaving it as an
inference.*

*Third, `SCAN` with cursors, because `KEYS` blocks the server on a large keyspace and is the
kind of thing that causes a real outage.*

*If I had longer than that: `MULTI`/`EXEC`, pub/sub, and RESP3 — but each is a subsystem,
and I'd rather have a small thing that's correct than a large thing that's half-done."*

---

# "What if" questions

These test design reasoning under changed constraints. There is no memorised answer;
practise thinking aloud.

| Question | The direction to take |
|---|---|
| **What if you had to support 100,000 connections?** | Event loop is the only viable mode of the two; but one core caps throughput, so the real answer is multiple event loops with `SO_REUSEPORT`, one per core, or sharding by key. |
| **What if commands became CPU-heavy?** | The single-threaded event loop head-of-line blocks. Move to a hybrid: event loop for I/O, a worker pool for expensive commands — which reintroduces the synchronisation I currently avoid. |
| **What if you needed real durability guarantees?** | `fsync=always` costs you the disk's flush rate. Real answer is replication — acknowledge after N replicas have it, which is durability without paying for a disk round-trip on the hot path. |
| **What if the dataset didn't fit in memory?** | It stops being Redis. You'd need an on-disk structure — LSM tree or B-tree — and the whole design changes. Better answer: shard, or accept eviction, which is what Redis does with `maxmemory-policy`. |
| **What if two clients hammer the same key?** | Virtual-thread mode: that key's bin lock is a contention point. Event-loop mode: no contention, but it's serialised anyway. Neither helps — the fix is application-level, e.g. sharding the counter. |
| **What if you had to add TLS?** | I'd stop hand-rolling and use Netty, or `SSLEngine` — which is famously awkward precisely because it interleaves with non-blocking I/O in exactly the partial-read/partial-write way this project already deals with. |

---

# Red flags — things not to say

| Don't say | Say instead |
|---|---|
| "It's production-ready" | "It's a learning project. Here's what's missing." |
| "It's as fast as Redis" | "Unpipelined we're within noise; pipelined Redis is 1.5–1.7× ahead." |
| "I'm an expert in NIO / Loom" | "I implemented this and here's what I learned." |
| "I built a Redis clone" *(implying completeness)* | "I implemented 63 commands and five data types. No replication, clustering, transactions or scripting." |
| Quoting a number you didn't measure | "I measured X on my machine; here are the caveats." |
| "Virtual threads make event loops obsolete" | "They make thread-per-connection viable. In my measurements the event loop was still 2.3× faster." |

---

# How to practise

1. **Say the 60-second pitch out loud** until it's comfortable. Record it once and listen.
2. **Trace `SET foo bar` end to end, out loud, with the code closed.** If you stall, that's
   the chapter of the learning guide to re-read.
3. **Work chain 3.2 all the way down** — event loop → NIO → partial reads → partial writes →
   why not Netty → why not virtual threads → when it stops working. That single chain covers
   most of what an interviewer will ask about this project.
4. **Have the three bugs from 3.5 ready**, one sentence each.
5. **Practise the unflattering answers.** "Redis is 1.5× faster than me pipelined" and "I
   inferred that but didn't confirm it" are the two sentences most likely to earn trust.

Anything you cannot yet explain from first principles is a gap in understanding, not a gap
in preparation. Go back to `docs/LEARNING_GUIDE.md` and the code.
