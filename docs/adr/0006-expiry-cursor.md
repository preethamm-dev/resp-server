# ADR 0006 — A rotating cursor for active expiry, not random sampling

**Status:** accepted · **Date:** day 2

## Context

Lazy expiry — treating a key past its deadline as absent and deleting it on access — is
correct but not sufficient. A key nobody reads again is never touched, so its memory is
never reclaimed. A workload that writes a million session keys with a one-hour TTL and then
stops reading them holds all million forever.

Something has to go looking. It cannot scan the whole keyspace: on a large database that
would stall the server.

Redis samples 20 keys at random from a **dedicated dictionary of keys that have a TTL**,
deletes the expired ones, and repeats while more than a quarter of a sample was expired.

## Decision

Keep Redis's adaptive loop exactly, but obtain the sample with a **rotating cursor over the
whole keyspace** rather than random selection from a TTL index.

## Consequences

**The adaptive loop is the valuable part and it is preserved.** Sample 20, delete the
expired, continue while more than a quarter were expired, cap at 16 rounds. The feedback is
what makes it cheap: if a quarter of a random-ish sample has expired then a similar
fraction of the keyspace probably has, and it is worth continuing; otherwise it stops after
one cheap round. The cap prevents a fully-expired keyspace turning a memory problem into a
latency one.

**Random selection was not available.** `ConcurrentHashMap` offers no O(1) random key.
Getting one means either snapshotting the key set — O(n) allocation every cycle, defeating
the purpose — or maintaining a parallel index.

**A parallel TTL index would put bookkeeping on the hot path.** Every `SET`, `SETEX`,
`EXPIRE`, `PERSIST` and `DEL` would have to update a second structure, so that a background
task could go faster. That is the wrong trade for this server: commands are the hot path,
the reaper is not.

**The cursor wastes effort on keys with no TTL.** Where Redis looks only at volatile keys,
this looks at whatever comes next. On a keyspace that is mostly permanent, most of each
sample is wasted. Bounded and cheap, but genuinely worse than Redis.

**Coverage is eventual rather than probabilistic.** The cursor wraps, so every key is
examined within a bounded number of cycles —
`ExpiryManagerTest.cursorWrapsAroundSoEveryKeyIsEventuallyExamined` asserts exactly that.
Arguably more predictable than sampling, which can miss a key indefinitely by bad luck.

## What this is not

Not a correctness mechanism. A key is **logically gone** the instant its deadline passes,
because every read path checks. The reaper only reclaims memory, so being late is a memory
question and never a correctness one. That is why best-effort is acceptable here and would
not be if expiry depended on it.

## Alternatives

**A `DelayQueue` or priority queue of deadlines.** Exact, no sampling, wakes precisely when
the next key expires. Rejected because every `SET` with a TTL would pay a O(log n) enqueue,
`PERSIST` and overwrite would need removal from the middle, and the queue would hold a
strong reference to every volatile key — moving the memory problem rather than solving it.

**A parallel TTL index, matching Redis.** The correct answer at scale, and what this would
become if the keyspace were mostly volatile. Rejected for a three-day build because the
hot-path cost is paid always and the benefit only shows on a workload this server is not
being measured on.

**Scan everything on a timer.** Simple, and O(n) per cycle. Fine at a thousand keys,
unusable at ten million — and choosing an approach that fails at scale is exactly what the
sampling design exists to avoid.
