# ADR 0005 — Command-log persistence, gated on a dirty counter

**Status:** accepted · **Date:** day 2

## Context

Redis offers two persistence strategies. **RDB** takes a point-in-time snapshot of the
whole dataset. **AOF** appends every dataset-changing command to a log and replays it at
startup.

## Decision

Implement AOF only, and record a command **only when it actually changed the dataset**.

## Consequences

**The protocol is already a serialisation format.** The log holds the RESP arrays the
clients sent, so no format had to be designed, the file is readable, and
`redis-cli --pipe < appendonly.aof` would replay it into a real Redis.

**Recovery reuses the command path.** `AofLoader` dispatches through the same
`CommandRegistry` as a live connection. A second "apply a command" implementation would be
one more thing to keep in step, and its bugs would only surface after a crash — the worst
possible moment to discover them.

**A truncated tail is handled by the parser, not by special code.** A crash mid-append
leaves an incomplete frame; that is exactly "not enough bytes yet". The loader replays
every complete command, discards the fragment, and truncates the file — leaving it would
corrupt everything written afterwards, because the fragment would be read as the start of
the next record.

**The file grows with writes, not with data.** A counter incremented a million times is one
key and a million records. Redis periodically rewrites the log as the shortest command
sequence that recreates the current state. **That rewrite is not implemented here**, and it
is the most significant missing piece of the persistence story.

**Appends serialise on one lock.** Not only for safety of the file handle: the log must
record the same order the keyspace applied, or replay produces a different dataset. Under
the virtual-thread model every writing client funnels through that lock — a real
bottleneck that single-threaded Redis does not pay.

## The dirty counter, and why it is not optional

Appending every write command looks harmless and is not.

`SET k v NX` against an existing key **changes nothing** — it replies nil. But replaying it
into an empty keyspace at startup **would** create the key. Recovery would produce a
different dataset from the one that was saved, and the divergence would be invisible until
someone read that key.

So `Database` keeps a counter of operations that genuinely mutated state, and
`CommandExecutor` samples it either side of the handler, persisting only when it moved.
Redis solves the identical problem with its `server.dirty` counter.

Collection mutations mark dirty unconditionally, which over-records slightly. That is safe:
replaying an `SREM` of an absent member or an `LPOP` of a missing key is a no-op. The
conditional string writes cannot take that shortcut, because their replay is *not* a no-op.

`AofPersistenceIT.failedConditionalWritesAreNotPersisted` exists specifically to pin this
down.

## Alternatives

**RDB snapshots.** Compact, fast to load, bounded by data size rather than write count.
Rejected because the interesting engineering — crash consistency, torn records, fsync
policy — lives in the log. A snapshot is either complete or absent, which is a less
instructive failure mode.

**Both**, as Redis does. Correct for production, more than three days allows.

**A write-ahead log with checksums per record.** Would catch bit rot that RESP framing
does not. Deferred; the framing already detects truncation, which is the failure this
project can actually demonstrate.
