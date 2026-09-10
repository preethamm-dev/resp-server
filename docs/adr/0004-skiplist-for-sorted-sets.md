# ADR 0004 — A hand-written skip list for sorted sets

**Status:** accepted · **Date:** day 2

## Context

A sorted set needs three things at once:

| operation | requirement |
|---|---|
| `ZSCORE member` | O(1) — one of the most frequent commands |
| `ZRANGE start stop` | ordered traversal |
| `ZRANK member` | how many members precede this one |

Java ships `TreeMap`, a red-black tree with the same O(log n) bounds as a skip list.

## Decision

Hold **both** a `HashMap<Bytes, Double>` and a hand-written `SkipList`, keeping them in
step. This is what Redis does.

## Consequences

**Neither structure alone is sufficient.** A skip list makes `ZSCORE` O(log n) at best; a
hash map cannot answer `ZRANGE` at all.

**`ZRANK` is the reason the skip list is hand-written rather than a `TreeMap`.** A balanced
tree answers "how many elements precede this one" in O(n) — you have to count — unless
every node also stores its subtree size, which then has to be repaired up the entire path
on every rotation. A skip list stores a **span** on each forward pointer: how many
bottom-level nodes that pointer jumps. Accumulating spans during the ordinary search
yields the rank for free, with no extra traversal and no rebalancing to keep consistent.

**Range scans are natural.** The bottom level is an ordered linked list, so `ZRANGE` seeks
once and walks. A tree needs repeated successor lookups.

**No rotations.** Insert and delete only relink forward pointers. Far easier to get right
than red-black rebalancing, and far easier to read.

**The two structures can diverge**, and that is the real cost. A member in one but not the
other is a corrupt collection. Every mutation is therefore written as "remove the old pair
from both, insert the new pair into both" rather than updating in place, and
`checkInvariants()` verifies they agree.

**The bounds are expected, not worst case.** An unlucky run of coin flips degrades to a
linked list. With p=0.25 and 32 levels that is vanishingly unlikely; Redis makes the same
bet.

## Why the tests are unusual

A skip list is randomised: no two runs build the same shape, so a fixed sequence of inserts
exercises a different structure every time and a bug can hide for many runs.

More importantly, **a span bug is silent**. Ordering stays correct, iteration returns
everything, `ZRANGE` looks perfect — and only `ZRANK` returns wrong numbers. A test that
checks output order would never catch it.

So `SortedSetTest` runs randomised workloads against a `TreeSet` oracle and compares
**rank**, not just order, and `checkInvariants()` independently verifies that every span
equals the real distance it claims to jump.

## Alternatives

**`TreeMap` plus a `HashMap`.** Would work for `ZSCORE` and `ZRANGE`; `ZRANK` becomes O(n).
Acceptable for a toy, but `ZRANK` is a headline sorted-set operation and making it linear
would misrepresent what a sorted set is.

**An order-statistic tree** — a red-black tree with subtree sizes. Correct and O(log n),
and strictly harder: size maintenance across rotations is fiddlier than span maintenance
across pointer relinking, which needs none.

**A sorted `ArrayList`.** O(1) rank by index, O(n) insert. Fine to a few hundred elements,
wrong beyond that.
