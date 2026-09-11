# resp-server — Learning Guide

**This document is for me.** Not for recruiters, not for senior engineers. It is written so
that I can read it on my own, without needing someone to explain the vocabulary, and end up
able to close it and explain the system myself.

Rules it follows:

- **Every unfamiliar term gets a plain-English definition the first time it appears.**
- **Every concept goes simple → example → technical → the actual code.**
- **Every section is tagged** so I know how deeply to learn it:

| Tag | Meaning |
|---|---|
| 🔴 **Must understand deeply** | I will be asked about this. Do not move on until I can explain it without notes. |
| 🟡 **Should understand** | Know what it is and why it is there. Being able to discuss it is enough. |
| 🟢 **Nice to know** | Read once. Do not spend study time memorising it. |

> Having this guide does not mean I know this material. It is the textbook, not the exam.

---

## Chapter 0 — How to use this

**First read (2 hours).** Chapters 1–3. Skip anything tagged 🟢. Goal: be able to draw the
system on paper and say what happens when someone types `SET foo bar`.

**Second read (the real work).** Chapters 4–11, one per sitting, with the code open. Each has
a *"Before this chapter"* box naming what to learn first.

**Coming back after weeks away?** Read chapter 3 (the journey of one command). It rebuilds the
whole mental model faster than anything else here.

**At the end of every chapter** there are questions. If I cannot answer them with the code
closed, the chapter is not finished.

---

# Chapter 1 — What this project is

🟡 **Should understand**

## The one-sentence version

I built a server that pretends to be Redis, well enough that the real Redis command-line tool
connects to it and cannot tell the difference.

## The words in that sentence

**Redis:** a very popular piece of software that stores data in memory (RAM) rather than on
disk, so reading and writing it is extremely fast. Most web applications use it as a cache — a
place to keep answers you have already worked out, so you do not work them out again.

**Server:** a program that waits for other programs to connect to it over a network and asks
it to do things. It does not have a screen or a user; it has clients.

**Client:** the program on the other end. `redis-cli` is a client. So is a Java application
using a library like Jedis.

**Protocol:** the agreed format for those conversations. If the client sends bytes the server
does not expect, nothing works. Redis's protocol is called **RESP**.

## Why build one

Not because the world needs another Redis. It does not. I built it because I used caches,
databases and network servers every day without ever having seen inside one. Writing this
forced me to actually make things work that I had only read about: that a network connection
delivers a stream of bytes rather than messages, that saving a file to disk is not the same
as the data being safe, that "single-threaded" can be an advantage.

## What it actually does

63 commands. Five kinds of data. Keys that expire on a timer. Saves to disk and survives being
killed. And it exists in **two completely different designs for handling many clients at
once**, which I benchmarked against each other and against real Redis.

That last part is the interesting bit, and it is what chapter 8 is about.

**Questions before moving on:**
1. In my own words, what is Redis for?
2. Why does a protocol need to exist at all?

---

# Chapter 2 — The architecture, in plain terms

🔴 **Must understand deeply**

Before the diagram, the two words that matter most.

**TCP:** the rules computers use to send data reliably over a network. It guarantees your
bytes arrive, in order, without gaps. It does **not** guarantee anything about how they are
grouped. This single fact shapes half of this project. Chapter 4 is entirely about it.

**Socket:** the thing your program actually reads from and writes to for one network
connection. Think of it as a file that happens to be a conversation with another computer.

## The shape of it

```
                    a client (redis-cli, Jedis, redis-benchmark)
                                    │
                                    │  TCP
                      ┌─────────────┴──────────────┐
                      │   how do we handle many     │
                      │   clients at once?          │
                      └─────────────┬──────────────┘
              ┌──────────────────────┴──────────────────────┐
              │                                              │
    ┌─────────▼──────────┐                      ┌───────────▼───────────┐
    │ VirtualThreadServer│                      │   EventLoopServer     │
    │                    │                      │                       │
    │ one worker per     │                      │  one worker, watching  │
    │ client             │                      │  all clients           │
    └─────────┬──────────┘                      └───────────┬───────────┘
              └──────────────────────┬──────────────────────┘
                                     │
                              ┌──────▼───────┐
                              │  RespReader  │   turn bytes into a command
                              └──────┬───────┘
                                     │
                           ┌─────────▼──────────┐
                           │  CommandRegistry   │   find and run the command
                           └─────────┬──────────┘
                                     │
                              ┌──────▼───────┐
                              │   Database   │   the actual data
                              └──────┬───────┘
                                     │
                              ┌──────▼───────┐
                              │  RespWriter  │   turn the answer back into bytes
                              └──────────────┘
```

## The two things to take from this

**1. It forks at the top and rejoins immediately.** There are two ways of handling clients,
and *everything below them is shared*. That is deliberate. It means when I benchmark the two
against each other, I am comparing the two designs — not two different servers that happen to
differ in many ways.

**2. Each layer only knows about the one below it.**

| Layer | Knows about | Has never heard of |
|---|---|---|
| `server/` | sockets, bytes | sorted sets, `GET` |
| `protocol/` | bytes, RESP | `GET`, `SET` |
| `command/` | commands | sockets |
| `store/` | data | RESP, networks |

This is why two totally different network designs can share the same command code.

## Where the code lives

```
src/main/java/com/preetham/respserver/
├── protocol/   bytes ↔ values        RespReader, RespWriter, ReadBuffer, WriteBuffer
├── command/    dispatch              CommandRegistry, CommandExecutor, impl/*Commands
├── store/      the data              Database, RedisString, RedisList, SkipList, ...
├── server/     sockets               VirtualThreadServer, EventLoopServer, Connection
└── persistence/ saving to disk       AofWriter, AofLoader
```

**Questions:**
1. Why do both servers sit behind one interface?
2. If I wanted to add a new command, which package would I touch — and which would I not?

---

# Chapter 3 — The journey of one command

🔴 **Must understand deeply** — *this is the most useful section in the guide*

I type `SET foo bar` into `redis-cli`. Here is everything that happens.

## Step 1 — the client turns it into bytes

`redis-cli` does **not** send the text `SET foo bar`. It sends this:

```
*3\r\n$3\r\nSET\r\n$3\r\nfoo\r\n$3\r\nbar\r\n
```

`\r\n` is two invisible characters: carriage return and line feed. Together they mean "end of
line" — the same thing you get when you press Enter in a text file on Windows.

Decoded:

| Piece | Means |
|---|---|
| `*3` | an array of 3 things follows |
| `$3` | the next thing is 3 bytes long |
| `SET` | ...here it is |
| `$3` `foo` | 3 bytes: `foo` |
| `$3` `bar` | 3 bytes: `bar` |

**Why send the length before each value?** Because then the value can contain *anything* —
including `\r\n` itself. If I store a photograph as a value, the bytes of that photo will
contain sequences that look like line endings. Because the server was told "the next 4096
bytes are the value", it does not care what is in them. This is called being **binary safe**.

*In the code:* `protocol/RespValue.java` models these five types as a sealed interface.

## Step 2 — the bytes arrive, possibly in pieces

🔴 **This is the single most important idea in the project.**

The server asks the operating system for whatever has arrived. It might get:

- all 31 bytes at once, or
- the first 9 bytes, or
- 1 byte, or
- those 31 bytes *plus* the first half of the next command

**Nothing guarantees one read gives you one command.** Chapter 4 explains why in full.

## Step 3 — keep what arrived

Whatever arrived is appended to a buffer belonging to *that specific client*.

**Buffer:** a chunk of memory used to hold data temporarily. Here it holds bytes received but
not yet understood.

It has to be per-client, because a half-finished command from client A must not get mixed up
with bytes from client B.

*In the code:* `protocol/ReadBuffer.java`

## Step 4 — try to parse, and be willing to fail

```java
while (true) {
    Optional<RespValue> frame = reader.tryParse(parsing);
    if (frame.isEmpty()) break;   // not a whole command yet — wait for more
    execute(frame.get());
}
inbound.consume(parsing.position());   // throw away exactly what we used
```

`tryParse` does one of three things:

1. **A whole command is there** → return it, and mark those bytes as used.
2. **Only part of one is there** → return nothing, and **put the buffer back exactly as it
   was**.
3. **These bytes can never be valid** → throw an error.

That `while` loop is also, by accident, the entire implementation of **pipelining**.

**Pipelining:** when a client sends many commands at once without waiting for each reply. If a
client sends 100 commands in one go, this loop parses and answers all 100 in one pass. I wrote
no special code for it — it fell out of the shape.

*In the code:* `protocol/RespReader.java`

## Step 5 — find and run the command

`command/CommandRegistry.java`:

1. Look up `set` in a map of command name → handler.
2. Check the number of arguments is right.
3. Run it.

## Step 6 — change the data

`StringCommands.set` reads any options (`EX`, `NX`, ...) and calls
`Database.set("foo", new RedisString("bar".getBytes()))`.

That is a `put` into a map, plus incrementing a counter (chapter 7 explains that counter).

## Step 7 — write it to the save file, if anything actually changed

If the command changed data, it is appended to a file on disk so it survives a restart.
Chapter 7.

## Step 8 — turn the answer back into bytes

`RespWriter` turns `OK` into `+OK\r\n` and it goes back down the socket.

**Important detail:** the reply is *buffered* and flushed once after the whole batch. So 100
pipelined commands cost **one** write to the network instead of 100.

**Questions:**
1. Why is the length sent before each value?
2. At which step could the command arrive in two pieces, and what stops that breaking?
3. Where does pipelining get implemented? (Trick question.)

---

# Chapter 4 — Why the parser is the way it is

🔴 **Must understand deeply**

*Before this chapter: know what TCP and a socket are — chapter 2.*

## Simple version

TCP promises your bytes arrive in order and none go missing. It promises **nothing** about how
they are grouped.

## An example

I send `SET foo bar` — 31 bytes. The server calls "read" and might get:

```
read 1:  *3\r\n$3\r\nS          ← 9 bytes. Half a word.
read 2:  ET\r\n$3\r\nfoo\r\n$3\r\nbar\r\n
```

Or the opposite — a client sends three commands quickly and one read returns all three plus
half of a fourth.

## Why it happens

The network splits data into packets. The operating system hands you whatever has arrived when
you ask. Neither has any idea where your commands begin or end — that is *your* protocol's
concern, not TCP's.

## Why it matters so much

The tempting shortcut is to assume one read = one command. It works perfectly:

- on your laptop
- over a local connection
- with small commands
- under no load

and then fails in production, under load, across a slow network — where debugging is hardest.

## What the code does

`RespReader.tryParse` has a strict contract:

| Situation | Result |
|---|---|
| a whole command is present | return it, buffer advanced past it |
| only part of one | return empty, **buffer position exactly where it started** |
| bytes that can never be valid | throw `ProtocolException` |

That middle row has to be *exact*. If it consumed even one byte of an incomplete command, the
stream would be corrupted — and only under fragmentation, which is the hardest thing to
reproduce.

**Keeping "not enough yet" separate from "broken" is the whole idea.** Confusing them is the
classic bug: a split command gets rejected as a protocol error.

## What I got for free by doing this on day one

🟡

1. **Pipelining** — just loop until the parser says "no more".
2. **The event loop on day 3** — a non-blocking read *cannot* wait for the rest of a command,
   so an incremental parser is not an optimisation there, it is a requirement. Had I not built
   it first, day 3 would have meant rewriting the parser and both servers.
3. **Crash recovery** — a save file cut off by a crash is exactly "not enough bytes yet" at the
   end. The recovery code needed no special parsing at all.

## How I know it works

`IncrementalParseTest` feeds every command **one byte at a time** and asserts the parser
returns nothing until the final byte — and that it consumed *zero* bytes each time. It also
tests all 30 possible split points of a `SET`.

## 🐛 A real bug this area produced

While building the crash-recovery demo, a script loading 5,000 keys loaded **zero**. No error.
The server just sat there.

Cause: the shell's `echo` ends lines with `\n`. My parser for typed-in commands demanded
`\r\n`. So the command never completed and the server correctly waited forever for a carriage
return that was never coming.

The fix drew a line I now think is right:

- **Machine-generated RESP stays strict** — the spec says `\r\n`, and being lenient there would
  hide genuine corruption.
- **Typed-in commands accept a bare `\n`** — because humans and shells do not reliably send
  carriage returns, and real Redis accepts them too.

**Questions:**
1. What exactly happens if a read returns the first 9 bytes of a `SET`?
2. Why is a malformed command fatal to the connection when a wrong-argument-count is not?
3. Name two things that became easy because the parser was built this way first.

---

# Chapter 5 — Storing the data

🟡 **Should understand** (except the atomicity part, which is 🔴)

*Before this chapter: know what a `HashMap` is.*

## The five types

Redis is not a plain "key → text" store. Each key holds a *typed* thing:

| Type | What it is | Backed by |
|---|---|---|
| string | bytes | `byte[]`, immutable |
| list | ordered, push/pop from both ends | `ArrayDeque` |
| hash | a small map inside a key | `LinkedHashMap` |
| set | unique values | `LinkedHashSet` |
| sorted set | unique values, each with a score | `HashMap` + skip list (chapter 6) |

**Why `ArrayDeque` for lists?** A list is usually used as a queue or a stack, so what matters
is adding and removing at *both ends*. `ArrayDeque` does that instantly. An `ArrayList` would
have to shift every element to add at the front.

## Empty collections do not exist

Pop the last item off a list and **the key disappears**. `EXISTS` then says 0. There is no such
thing as an empty list in Redis.

Handled in one place — `Database.mutateCollection` removes the key when a change leaves the
collection empty — rather than in every command.

(Strings are the exception: `SET k ""` is a real key holding nothing.)

## 🔴 Atomicity — the part that matters

**Concurrency:** several things happening at the same time. Here, several clients being served
simultaneously.

**Race condition:** when two of them touch the same data at the same time and the result
depends on who got there first. Usually a bug.

### The example

`INCR counter` means: read the number, add one, write it back.

Two clients do it at the same time, both starting from 5:

```
client A reads 5
client B reads 5          ← before A has written
client A writes 6
client B writes 6         ← should have been 7
```

One increment vanished. Do that a million times and the count is badly wrong.

### The fix

```java
keyspace.compute(key, (k, existing) -> {
    // read, parse, add, write — all in here
});
```

`ConcurrentHashMap.compute` locks **just that one key's slot** for the duration.

**Lock:** a way of saying "only one thread at a time in here". Others wait.

So:

- two clients on **different keys** never wait for each other
- two clients on the **same key** are serialised — which is exactly right
- the whole read-modify-write is one indivisible step

**Why not just lock everything?** Because then the entire server would handle one command at a
time and there would be no point having many workers.

### How I know it works

`DatabaseTest.incrementsAreAtomicUnderContention` runs 100 threads × 1,000 increments and
asserts the answer is exactly 100,000. A naive read-then-write version comes out short.

## 🟢 A subtle detail worth seeing once

When a key expires, the code does this:

```java
keyspace.remove(key, holder);   // note: two arguments
```

not `remove(key)`. Two-argument remove means "remove only if the value is still the one I
looked at". Without it, this could happen:

```
we check: expired, should delete
                                  another client sets a fresh value here
we delete                         ← we just deleted the fresh value
```

Small, and the kind of detail that separates code that works in tests from code that works
under load.

**Questions:**
1. Explain a lost increment to someone who has not seen one.
2. Why does `compute` beat a single global lock?

---

# Chapter 6 — The skip list

🟡 **Should understand** the idea and *why*. 🟢 The exact insert algorithm.

*Before this chapter: know what a linked list is, and roughly what O(log n) means.*

## Why a sorted set needs two structures

A sorted set has to do three things well:

| Operation | Needs |
|---|---|
| "what is Alice's score?" | instant lookup → a hash map |
| "give me everyone in order" | ordering → not a hash map |
| "what position is Alice in?" | ordering **and** counting |

No single structure does all three, so `RedisSortedSet` holds **both** a `HashMap` and a skip
list over the same data. Real Redis does exactly this.

## What a skip list is

Start with a sorted linked list — each item points to the next:

```
1 → 3 → 5 → 6 → 7 → 9
```

Finding 9 means walking through everything. Slow.

Now add **express lanes**. Each item flips a coin; if it wins it also appears on a higher level
that skips more items:

```
L3  H ─────────────────────────────► 9
L2  H ──────────► 3 ───────────────► 9
L1  H ──► 1 ────► 3 ──► 5 ─────────► 9
L0  H ──► 1 ────► 3 ──► 5 ──► 6 ──► 7 ──► 9
```

To find something: start top-left, move right while the next item is still too small, then drop
a level. Like skipping stations on an express train before switching to the local line.

**The "balancing" is a coin flip.** No rotations, no rebalancing — which is why it is so much
easier to get right than a balanced tree.

## 🔴 Spans — the actual reason I wrote it by hand

Java already has `TreeMap`, which is sorted and fast. So why write a skip list?

**`ZRANK` — "what position is this member in?"**

A balanced tree cannot answer that quickly. You have to *count* everything before the item —
O(n) — unless every node also stores how many items are beneath it, which then has to be
repaired all the way up the tree every time it rebalances.

A skip list stores a **span** on every pointer: *how many bottom-level items this pointer jumps
over*.

```
L2  H ──span=3──► 3 ──span=4──► 9
L1  H ─span=1─► 1 ─span=2─► 3 ─span=2─► 5 ─span=2─► 9
```

Then rank is free — just add up the spans you jumped while searching. No extra structure, no
extra traversal, and nothing to repair because a skip list never rebalances.

## 🔴 Why the tests are unusual

**A span bug is silent.**

Get span maintenance wrong and: the ordering is still correct, iteration still returns
everything, `ZRANGE` still looks perfect. Only `ZRANK` quietly returns wrong numbers.

A test checking output order would never notice.

So `SortedSetTest`:

1. Runs thousands of random inserts and deletes.
2. Does the same to a `TreeSet` — a structure whose correctness is not in question.
3. Compares **rank**, not just order.
4. Separately runs `checkInvariants()`, which verifies every span really equals the distance it
   claims to jump.

This is the single most valuable testing idea in the project: **when the structure is random,
test it against something you trust rather than against fixed expectations.**

## 🟢 One detail

Members are compared with `Arrays.compareUnsigned`, not `Arrays.compare`. The normal version
treats bytes as signed, which would put `é` before `a`. Redis compares unsigned.

**Questions:**
1. Why does a sorted set need two structures?
2. What is a span, and why does it make `ZRANK` cheap?
3. Why would a test that only checks `ZRANGE` miss a span bug?

---

# Chapter 7 — Expiry and saving to disk

🟡 **Should understand.** The `write` vs `fsync` distinction is 🔴.

## Expiry: two mechanisms, both needed

**Lazy expiry.** Every read checks the deadline. A key past it is treated as gone and deleted
right then.

That makes expiry *correct* — a key is gone the instant it should be.

But it leaks. A key nobody ever reads again is never touched, so its memory is never freed.
Write a million session keys with a one-hour timer, stop reading them, and all million stay in
memory forever.

**Active expiry.** A background task goes looking:

```
every 100ms:
    up to 16 times:
        look at 20 random-ish keys
        delete the expired ones
        if fewer than 25% were expired, stop
```

The feedback loop is the clever bit. If a quarter of a sample has expired, probably a similar
fraction of everything has, so keep going. If not, stop after one cheap round. **Costs almost
nothing when there is nothing to do; works hard exactly when there is** — and never scans
everything, which on a big database would freeze the server.

*In the code:* `store/ExpiryManager.java`

🟢 *Where mine differs from Redis:* Redis picks keys at random from a separate list of keys
that have timers. `ConcurrentHashMap` cannot pick a random key cheaply, and keeping a second
list would mean extra work on every `SET` just to speed up a background task. So mine sweeps
with a rotating cursor instead. Same eventual coverage, some wasted looks.

## 🔴 Saving: `write` is not `save`

This is the most important idea in the chapter.

**`write()`** hands your bytes to the **operating system**. They sit in the OS's memory (the
"page cache") until it decides to put them on the disk — possibly tens of seconds later.

**`fsync()`** forces them onto the physical disk *now*.

The consequences:

| What fails | `write` only | `write` + `fsync` |
|---|---|---|
| your program crashes | **data survives** (the OS still has it) | survives |
| the machine loses power | **data is gone** | survives |

And `fsync` is *expensive* — milliseconds, which is thousands of times longer than the command
that produced the data.

So there is no correct answer, only a choice:

| Setting | Lost if the process crashes | Lost on power failure | Speed |
|---|---|---|---|
| `always` | nothing | nothing | slowest |
| `everysec` (default) | nothing | up to 1 second | good |
| `no` | nothing | unknown amount | fastest |

That is why it is a setting and not a decision baked into the code.

## What gets saved

**AOF (append-only file):** a file that records every command that changed data. Replaying it
rebuilds everything.

The clever part is that the file holds the commands *in the protocol's own format* — so no
save format had to be invented, and `redis-cli --pipe < appendonly.aof` would replay it into a
real Redis.

## 🔴 The trap I had to design around

Recording every write command sounds obviously fine. It is not.

`SET k v NX` means "set this **only if the key doesn't exist**". If the key does exist, it does
**nothing** and replies nil.

But replaying that command into an *empty* database at startup **would** create the key.

So the data after a restart would differ from the data that was saved — and nobody would notice
until they read that key.

The fix: `Database` counts operations that genuinely changed something, and
`CommandExecutor` checks that counter before and after each command. Only a real change gets
recorded. Redis solves the identical problem the same way.

`AofPersistenceIT.failedConditionalWritesAreNotPersisted` exists purely to pin this down.

## Crash recovery

A crash mid-write leaves half a command at the end of the file.

There is no way to guess the missing bytes, so recovery replays everything complete and
discards the fragment — then **truncates the file**, because leaving the fragment would corrupt
whatever gets written next.

And the parser made this almost free, for the exact reason it was built that way on day one:
it already distinguishes "not enough bytes yet" from "broken", and at the end of a file the
first one simply means "this record was never finished".

**It is proved, not asserted.** `scripts/crash-recovery-demo.sh` kills the server with
`SIGKILL` — a signal that cannot be caught, so no cleanup code runs at all — restarts it, and
checks. Real output:

```
killed pid 9267 with SIGKILL -- no hook, no flush, no close
restored    19021 commands, 19021 keys
PASS  20 sampled keys all present, server writable again
```

**Questions:**
1. Why does `everysec` lose nothing when the process crashes but up to a second on a power cut?
2. Why must a failed `SET ... NX` not be written to the save file?
3. Why is lazy expiry alone not enough?

---

# Chapter 8 — Two ways to handle many clients

🔴 **Must understand deeply.** This is the heart of the project.

*Before this chapter: chapters 2, 4, and the atomicity part of 5.*

## The problem

A hundred clients are connected. Each sends commands whenever it likes. How does one program
serve all of them?

## Approach 1: one worker per client

**Thread:** a worker inside your program that can run code. Several threads = several things
happening at once.

**Blocking:** when code stops and waits. "Read from this socket" blocks until data arrives.

The design is the obvious one:

```java
while (true) {
    int read = in.read(chunk);   // just wait here
    ...
}
```

Each client gets a thread. The thread waits for that client. Code reads like the problem.

### Why this used to be a bad idea

A normal thread carries an operating-system thread, which needs about **a megabyte of memory**
for its stack. Ten thousand clients = ten thousand threads = gigabytes of memory and an
overwhelmed scheduler.

That is why the industry moved to approach 2.

### What changed in Java 21

**Virtual thread:** a thread managed by Java itself rather than the operating system. When it
blocks, Java parks it on the heap — costing a few hundred *bytes* — and reuses the underlying
OS thread for someone else.

So the simple code above becomes viable again at client counts that used to require approach 2.

```java
Executors.newVirtualThreadPerTaskExecutor()
```

That single line is the whole change.

*In the code:* `server/VirtualThreadServer.java`, `server/Connection.java`

### What it costs

Many threads share one set of data, so **everything needs synchronising**: the concurrent map,
the locks on collections, the lock on the save file.

**Every piece of locking in `Database` exists for this design alone.**

## Approach 2: one worker watching everyone

Instead of asking a socket for data and waiting, ask the operating system **which sockets have
data right now**, handle only those, and go round again.

**Non-blocking:** an operation that returns immediately, telling you what it managed to do,
rather than waiting.

**Multiplexing:** watching many connections with one worker.

**Selector:** the Java object that does the watching. You register sockets with it and say what
you care about; `select()` waits until at least one is ready, then tells you which.

**SocketChannel:** Java's non-blocking version of a socket.

**OP_READ / OP_WRITE:** the flags you register saying "tell me when this socket has data to
read" / "tell me when this socket can accept more data".

```java
while (running) {
    selector.select(200);                 // wait until something is ready
    for (SelectionKey key : selectedKeys) {
        if (key.isAcceptable()) accept();
        if (key.isReadable())   handleRead(key);
        if (key.isWritable())   handleWrite(key);
    }
}
```

This is how Redis, nginx and Node.js all work.

*In the code:* `server/EventLoopServer.java`

### 🔴 The part that is genuinely hard: partial writes

Reading in pieces I had already solved (chapter 4). **Writing in pieces was new.**

`write()` on a non-blocking socket returns **how many bytes it actually took** — which can be
fewer than you offered, or zero. That happens whenever the client reads more slowly than the
server replies, which is completely normal.

With one worker, I cannot wait for it to clear — that would freeze everyone else. So:

1. Keep the leftover bytes in that client's `WriteBuffer`.
2. Register `OP_WRITE`: "tell me when this socket can take more".
3. When told, finish the write.
4. **Then unregister `OP_WRITE`.**

### 🔴 Step 4 is the classic bug

A socket with free space is *almost always* writable. If you leave `OP_WRITE` registered,
`select()` returns immediately — forever. The server keeps answering every request perfectly
correctly while **burning an entire CPU core doing nothing**.

**No functional test catches this.** Everything works. It just costs a core.

So I wrote a test that measures the event loop thread's CPU time across one idle second and
asserts it stays under 250ms. A spinning loop would consume close to 1000ms.

### 🟡 Backpressure

**Backpressure:** what you do when the other side cannot keep up.

A client that stops reading but keeps sending would make that write buffer grow until the
server runs out of memory. Past 64MB the connection is dropped. Redis has the same protection,
called `client-output-buffer-limit`.

### What it buys

**No synchronisation at all.** One thread touches everything, so all the locking approach 1
needs is simply unnecessary here.

**That is why real Redis is single-threaded.** Not a limitation — a deliberate trade.

### What it costs

- **Head-of-line blocking** — one slow command delays every other client.
- **One core**, no matter how many the machine has.

**Questions:**
1. Why was thread-per-client abandoned, and what changed?
2. What is a partial write, and what are the two `OP_WRITE` mistakes?
3. Which lines of `Database` would be deletable if only the event loop existed?

---

# Chapter 9 — What the measurements showed

🔴 **Must understand deeply** — *including what they do NOT prove*

## Setup

Three subjects, measured with the **official `redis-benchmark`** (deliberately not a tool I
wrote), warmup discarded, median of 3 runs, spread reported.

## Results

**Normal traffic, 50 clients:**

| | ops/sec | p99 latency |
|---|---:|---:|
| event loop | **123,609** | 0.487 ms |
| virtual threads | **53,619** | 0.967 ms |
| real Redis | 124,688 | 0.447 ms |

**Pipelined (16 commands at a time):**

| | ops/sec |
|---|---:|
| real Redis | **1,373,626** |
| event loop | 917,431 |
| virtual threads | 761,035 |

## 🔴 How to read this honestly

**The event loop is ~2.3× the virtual-thread version.** Clear, consistent, well outside noise.

**Unpipelined it matches Redis — but that is NOT a claim of equivalence.** At that rate *both*
servers spend most of their time in operating system calls. The actual data work disappears
underneath. It is not that my implementation is as good; it is that the bottleneck is
elsewhere.

**Pipelined, Redis is 1.5–1.7× ahead.** Pipelining removes the syscall bottleneck, so what
remains is per-command work — parsing, hashing, allocating — and there, C with fifteen years of
optimisation beats straightforward Java. **That is the configuration that actually measures the
server, and mine loses it.**

Saying that part out loud is what makes the rest believable.

## 🟡 The anomaly I investigated

The virtual-thread number is suspiciously flat: ~53–55k for *every* command, at both 50 and 500
clients. A number that ignores both the work and the client count is a bottleneck in the
plumbing, not the commands.

Pipelining located it:

| | unpipelined → P16 | gain |
|---|---:|---:|
| virtual threads | 53,619 → 761,035 | **14.2×** |
| event loop | 123,609 → 917,431 | 7.4× |
| Redis | 124,688 → 1,373,626 | 11.0× |

Virtual threads gain the *most* from pipelining. Pipelining changes exactly one thing: how many
commands arrive per read. So whatever limits them scales with the number of **reads**, not
commands — pointing at the per-read cost of the blocking path.

**I did not confirm this.** It is an inference the data supports. Confirming it needs profiling
I did not do. Saying it that way is important.

## 🔴 What these numbers do NOT prove

- A laptop under WSL2 with 3.7GB RAM. Not a server.
- Loopback only — no real network.
- `redis-benchmark` itself competes for the same 8 cores at high rates.
- Short runs from an empty database.
- One machine, one JDK, one day.
- **Not** that this is as fast as Redis.

Being able to say all of that unprompted is worth more than any number in the table.

**Questions:**
1. Why is "matches Redis unpipelined" not a claim of equivalence?
2. Why does pipelining help virtual threads more than the event loop?
3. Name four things these benchmarks do not prove.

---

# Chapter 10 — How the tests prove it works

🟡 **Should understand**

267 tests. Three ideas in them are worth keeping.

## 🔴 Idea 1: test against something you did not write

The integration tests drive the server with **Jedis** — a real Redis client library written by
people who have never seen my code.

**Why this matters:** every other test checks my server against *my own understanding* of the
protocol. If my understanding is wrong, my tests are wrong in exactly the same way and agree
with each other perfectly.

**It found a real bug.** Jedis's `setnx()` sends a *different command* than I expected — the old
standalone `SETNX` rather than `SET` with an option. My `SET` was perfect. The server was
unusable from a real client. No test I wrote myself could have found that.

## 🔴 Idea 2: use an oracle for random structures

Explained in chapter 6. A skip list builds a different shape every run, and a span bug is
invisible to ordinary assertions. Comparing against a `TreeSet` catches what fixed expectations
cannot.

## 🔴 Idea 3: test the thing no functional test can see

The busy-loop test measures **CPU time**, not behaviour — because the bug it hunts produces
perfectly correct behaviour while wasting a core.

## 🟢 The suite at a glance

| Suite | Proves |
|---|---|
| `RespCodecTest` | every type encodes and parses; malformed input rejected; round-trips |
| `IncrementalParseTest` | survives arbitrary fragmentation, one byte at a time |
| `DatabaseTest` | expiry on a fake clock; 100 threads lose no increments |
| `SortedSetTest` | skip list matches a `TreeSet` oracle on **rank** |
| `ExpiryManagerTest` | unread keys still reclaimed; work is bounded |
| `CompatibilitySuite` | Jedis drives it — **run twice, once per server design** |
| `EventLoopStressIT` | 16MB replies, slow readers, 200 clients, the CPU test |
| `AofPersistenceIT` | restart, truncated file repaired, failed `SETNX` not saved |

🟡 Expiry is tested with a **fake clock** rather than `Thread.sleep`. Sleeping makes tests slow
and flaky on a loaded machine; moving a counter is instant and exact.

**Questions:**
1. Why is testing with Jedis stronger than testing with my own client?
2. What kind of bug does an oracle catch that normal assertions do not?

---

# Chapter 11 — Limitations and the real bugs

🟡 **Should understand** — *interviewers ask about both*

## What this does not do

No replication, clustering, transactions (`MULTI`), Lua scripting, pub/sub, blocking commands
(`BLPOP`), `SCAN`, RESP3, or snapshots. One database, not sixteen.

## Real limitations of what I did build

- **`MSET` is not atomic across keys** under the virtual-thread design. It writes one key at a
  time, so a concurrent reader can see half a batch. Real Redis avoids this by being
  single-threaded. Fixing it would need a global lock costing more than it buys — and the
  event-loop mode does not have the problem.
- **The save file is never compacted.** It grows with the number of *writes*, not the amount of
  data: a counter incremented a million times is one key but a million records. Redis rewrites
  the log periodically. **This is the biggest gap.**
- **Save-file writes go through one lock**, because the file has to record the same order the
  data was changed in. Under many writers that is a real bottleneck.
- **Active expiry sweeps rather than sampling randomly** — wasted looks at keys with no timer.
- **The virtual-thread plateau is explained by inference, not profiling.**

## 🔴 The real bugs — have three ready

*"Tell me about a bug you found" is a standard question. These are real and in the git history.*

**1. `INCR` silently corrupted data.**
Java's `Long.parseLong` accepts a leading `+`. Redis does not. So `INCR` on a value of `"+1"`
succeeded and rewrote the client's stored text to `"2"` — data changed without anyone asking.
Found by a test listing non-canonical numbers: `" 1"`, `"1.0"`, `"007"`, `"+1"`. Redis rejects
all of them because accepting them would make `INCR` lossy.

**2. Missing legacy commands — found only by a third-party client.**
See chapter 10. The clearest possible justification for testing against Jedis.

**3. Bulk loading silently loaded nothing.**
See chapter 4. `echo` sends `\n`, my parser demanded `\r\n`. No error, no timeout — the server
correctly waited forever.

🟢 Two more, smaller but real:

**4.** `SkipList.Entry` was leaking a package-private type into a public method signature.
Fixed by adding a public `ScoredMember` rather than making the skip list public — which
structure backs a sorted set should stay an implementation detail.

**5.** While writing the event loop I defined a nested `ClosedSelectorException` that *shadowed*
`java.nio.channels.ClosedSelectorException` — so the real one thrown by `select()` would never
have been caught. Spotted by reading the code before compiling.

---

# Chapter 12 — What to study, in order

🔴

Honest estimates. Do not compress the first two.

| # | Topic | Hours | For |
|---|---|---:|---|
| 1 | **Java concurrency** — threads, `synchronized`, locks, the memory model, `happens-before`, concurrent collections | 12–16 | Ch 5, 8 |
| 2 | **TCP framing** — byte stream vs messages, partial reads/writes | 5–6 | Ch 3, 4 |
| 3 | **Virtual threads** — carriers, parking, pinning | 5–6 | Ch 8 |
| 4 | **NIO and event loops** — `Selector`, interest sets, `epoll` | 8–10 | Ch 8 |
| 5 | **Benchmark method** — percentiles, warmup, noise | 4–5 | Ch 9 |
| 6 | **Skip lists** | 4–5 | Ch 6 |
| 7 | **Durability** — `write` vs `fsync`, the page cache | 3–4 | Ch 7 |
| 8 | **Collections internals** — `HashMap`, `ConcurrentHashMap`, `ArrayDeque` | 4–5 | Ch 5 |
| 9 | **Reading this codebase closely** | 12–15 | — |
| 10 | **Saying it out loud** | 10–12 | `INTERVIEW_GUIDE.md` |

**≈ 65–85 hours** for this project.

**Start with chapter 3 and topic 2.** Between them they unlock more of the system than anything
else.

**One term I have used without defining:** *happens-before* — the Java rule guaranteeing that
if thread A writes something and then releases a lock, thread B acquiring that lock will
definitely see the write. Without such a guarantee the compiler and CPU are free to reorder
things, and one thread can see stale data indefinitely. It is why locks are about *visibility*,
not just mutual exclusion. It is topic 1's hardest idea and worth the time.

---

# Chapter 13 — Self-check

🔴 If I cannot answer one of these with the code closed, that chapter is not finished.

1. Trace `SET foo bar` from `redis-cli` to `+OK`, naming each layer.
2. Why must the parser be incremental? What breaks otherwise?
3. Why does the dispatcher never throw, and what is the one case that closes a connection?
4. How is `INCR` made atomic without locking the whole server?
5. Why does a sorted set need two structures?
6. What is a span, and why is `ZRANK` the reason I hand-wrote the skip list?
7. Why are lazy *and* active expiry both necessary?
8. Why does `everysec` lose nothing to a crash but a second to a power cut?
9. Why must a failed `SET ... NX` not go in the save file?
10. What is a partial write, and what are the two `OP_WRITE` bugs?
11. What does single-threading buy, and what does it cost?
12. What did the benchmarks show — and what do they *not* prove?
13. Name three real bugs and how each was found.
14. What would I do with three more days?

## 🔴 What I must never claim

- That this is production-ready or a Redis replacement.
- That it is as fast as Redis. **Pipelined, Redis is 1.5–1.7× ahead.**
- Any number I have not personally measured.
- Deep expertise in Netty, Loom internals, or Redis's C source.
- That I implemented replication, clustering, transactions, scripting or pub/sub.

An honest *"I didn't implement that — here's why, and here's what I'd need to learn"* scores
better than a bluff that collapses on the first follow-up.
