# ADR 0002 — No runtime dependencies

**Status:** accepted · **Date:** day 1

## Context

A Java network server would normally reach for Netty. It is excellent: mature, fast,
zero-copy buffers, a solid TLS story, and it has already met every edge case this project
will meet.

## Decision

The server has **zero compile-scope dependencies**. Only the JDK. Test scope has JUnit,
AssertJ, Awaitility and Jedis; nothing ships in the jar.

## Consequences

**Netty would have hidden the things worth learning.** Its whole value is that you do not
have to think about selectors, partial reads, partial writes or `OP_WRITE` registration.
Since thinking about exactly those is the point, importing it would have removed the
project's substance and left a configuration exercise.

**Everything that goes wrong is mine.** The `OP_WRITE` deregistration bug, the send-buffer
limit, the framing — all hand-written, so all defensible in detail, and all tested
directly (`EventLoopStressIT`).

**Cost:** slower, less complete and less battle-tested than Netty. No TLS. No zero-copy.
No `epoll` transport tuning. Buffer management is naive by comparison.

**The jar is 70 KB and starts instantly**, which is a genuine if incidental benefit.

## Alternatives

**Netty** — the right answer for production, the wrong one for a project whose subject is
the mechanism. Stated openly in the README rather than implied to be a rejection on merit.

**A minimal library such as Undertow's XNIO** — same objection, less upside.

## The line this draws

Not "never use libraries". Jedis is used in the tests precisely *because* it is
third-party: an independent implementation of the protocol is far stronger evidence of
compatibility than my own reader agreeing with my own writer. The rule is that the
**subject** of the project is hand-written and everything else is not.

In an interview the honest version is: *"I'd use Netty for anything real. I wrote this by
hand because I wanted to understand what Netty is doing for me, and now I do."*
