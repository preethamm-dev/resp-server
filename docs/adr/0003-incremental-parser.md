# ADR 0003 — Make the parser incremental from the first commit

**Status:** accepted · **Date:** day 1

## Context

TCP delivers a byte stream, not messages. A single `read()` may return half of
`SET foo bar`, or two and a half commands, or one byte. Nothing in the protocol prevents
any split at any offset.

The tempting shortcut is to assume one read yields one command. It passes every test
written with whole commands, works on a quiet loopback connection, and fails later under
load or across a slow link — where it is hardest to debug.

## Decision

`RespReader.tryParse(ByteBuffer)` either consumes **exactly one complete frame** and
advances the position, or consumes **nothing at all** and reports that more bytes are
needed. Only input that cannot become valid throws.

Written this way on day one, before either server existed.

## Consequences

**The event loop became possible.** A non-blocking read cannot wait for the rest of a
frame, so an incremental parser is not an optimisation there — it is a precondition. Had
this been deferred, day 3 would have meant rewriting the parser and everything above it.

**Pipelining is free.** The connection loop calls `tryParse` until it returns empty. If a
client sends 100 commands in one write, all 100 are parsed and answered in one pass with a
single flush. There is no pipelining code anywhere; it falls out of the shape.

**AOF recovery is free too.** A file whose last record was cut off by a crash is exactly
"not enough bytes yet" at the end of the buffer. `AofLoader` needed no special parsing —
it replays complete frames and stops when the parser says the last one is short.

**The contract must be exact.** On an incomplete parse the buffer position must be
*precisely* where it started; consuming even one byte would corrupt the stream in a way
that only appears under fragmentation. `IncrementalParseTest` feeds every frame one byte
at a time and asserts both the result and `position() == 0` at each step.

## Alternatives

**Parse from a blocking `InputStream`.** Much simpler — read as many bytes as you need and
block. Works fine for the virtual-thread server and is impossible for the event loop.
Rejected because it would have forced two parsers.

**Read a whole frame into a scratch buffer, then parse.** Needs the length up front, which
RESP only gives per element, so it degenerates into the same problem.

## The lesson

The cost of building it correctly on day one was perhaps two extra hours. The cost of
retrofitting it on day three would have been a rewrite of the parser, both servers and the
loader. This is the clearest case in the project of a decision paying for itself.
