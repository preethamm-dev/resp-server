# ADR 0001 — Build two concurrency models behind one interface

**Status:** accepted · **Date:** day 1, revisited day 3

## Context

Java 21 shipped virtual threads. The claim attached to them is that thread-per-connection
— the design non-blocking I/O was invented to escape — becomes viable again, because a
parked virtual thread costs a few hundred bytes rather than a megabyte of stack.

That claim is testable, and a Redis-compatible server is close to the ideal test case:
small, uniform, latency-sensitive requests where the I/O path dominates.

## Decision

Implement **both** models against a single `RedisServer` interface, sharing the command
registry and the keyspace, and benchmark them against each other and against real Redis.

- `VirtualThreadServer` — one virtual thread per connection, blocking I/O
- `EventLoopServer` — one thread, NIO `Selector`, non-blocking I/O

## Consequences

**The seam has to be narrow or the comparison is worthless.** If the two servers differed
in how they parsed, dispatched or stored, a benchmark would compare two servers rather
than two concurrency models. Everything above the transport is shared, and the entire
52-test compatibility suite runs against both (`VirtualThreadServerIT`,
`EventLoopServerIT`) so parity is enforced by the build rather than assumed.

**They do not have the same concurrency requirements**, and that is the interesting part.
The virtual-thread server has many threads on one keyspace, so it needs
`ConcurrentHashMap.compute` for atomic read-modify-write and per-instance locks on the
collection types. The event loop has one thread and needs none of that. The synchronisation
in `Database` exists solely for the first model.

**Cost:** roughly a day of the three, and a permanent obligation to keep both working.

## Alternatives

**Only virtual threads.** Simpler and modern. Rejected because the project's most
interesting question would have gone unanswered, and because "virtual threads are fast" is
a claim, not a measurement.

**Only an event loop.** What Redis does, and the fastest option here. Rejected for the
same reason, plus it would have made the code look like a Redis translation rather than an
investigation.

## What the measurement showed

Unpipelined, the event loop is roughly **2.3× the virtual-thread mode**, holding across
every command and both connection counts. Under pipelining the gap narrows, and the
virtual-thread mode gains far more from pipelining (14.2×) than the event loop does (7.4×)
— evidence that its bottleneck scales with read operations rather than commands.

The decision was worth making: the answer was not the one the marketing suggests, and it
came with data. See `docs/benchmarks.md`.
