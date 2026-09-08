package com.preetham.respserver.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A value in the RESP2 wire protocol.
 *
 * <p>RESP2 has five types, each identified by its first byte:
 * <pre>
 *   +OK\r\n                     simple string
 *   -ERR unknown command\r\n    error
 *   :1000\r\n                   integer
 *   $5\r\nhello\r\n             bulk string  (length-prefixed, binary safe)
 *   *2\r\n$3\r\nGET\r\n$3\r\nfoo\r\n   array
 * </pre>
 *
 * <p>Two special forms encode absence: {@code $-1\r\n} (null bulk string, the
 * usual "key does not exist" reply) and {@code *-1\r\n} (null array, used by a
 * few commands such as a timed-out blocking pop).
 *
 * <p>Bulk strings hold raw bytes rather than {@code String} because Redis values
 * are binary safe -- a value may contain arbitrary bytes including NUL, and
 * round-tripping those through a charset would corrupt them.
 */
public sealed interface RespValue {

    /** {@code +OK\r\n} -- a short, non-binary status reply. May not contain CR or LF. */
    record SimpleString(String value) implements RespValue {
        public SimpleString {
            Objects.requireNonNull(value, "value");
        }
    }

    /** {@code -ERR message\r\n}. By convention the first word is an error code. */
    record ErrorReply(String message) implements RespValue {
        public ErrorReply {
            Objects.requireNonNull(message, "message");
        }
    }

    /** {@code :42\r\n} -- a signed 64-bit integer. */
    record IntegerReply(long value) implements RespValue {}

    /**
     * {@code $5\r\nhello\r\n} -- a length-prefixed, binary-safe byte string.
     *
     * <p>The array is not defensively copied: this type sits on the hot path and
     * copying every value would double the allocation cost of a reply. Treat the
     * contents as immutable by convention. {@code equals} and {@code hashCode} are
     * overridden because a record's generated versions compare arrays by identity,
     * which would make almost every test fail.
     */
    record BulkString(byte[] value) implements RespValue {
        public BulkString {
            Objects.requireNonNull(value, "value");
        }

        public String asString() {
            return new String(value, StandardCharsets.UTF_8);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof BulkString(byte[] other) && Arrays.equals(value, other);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(value);
        }

        @Override
        public String toString() {
            return "BulkString[" + asString() + "]";
        }
    }

    /** {@code *2\r\n...} -- an array of nested values. */
    record ArrayReply(List<RespValue> items) implements RespValue {
        public ArrayReply {
            items = List.copyOf(Objects.requireNonNull(items, "items"));
        }
    }

    /**
     * The two null forms. RESP2 distinguishes them on the wire ({@code $-1} vs
     * {@code *-1}) even though both mean "absent", so the writer needs to know
     * which one was intended.
     */
    enum Null implements RespValue {
        BULK,
        ARRAY
    }

    // ---- convenience factories -------------------------------------------------

    RespValue OK = new SimpleString("OK");
    RespValue PONG = new SimpleString("PONG");
    RespValue ZERO = new IntegerReply(0);
    RespValue ONE = new IntegerReply(1);
    RespValue EMPTY_ARRAY = new ArrayReply(List.of());

    static RespValue bulk(String s) {
        return new BulkString(s.getBytes(StandardCharsets.UTF_8));
    }

    static RespValue bulk(byte[] b) {
        return new BulkString(b);
    }

    static RespValue integer(long v) {
        return new IntegerReply(v);
    }

    static RespValue simple(String s) {
        return new SimpleString(s);
    }

    static RespValue error(String message) {
        return new ErrorReply(message);
    }

    static RespValue array(List<RespValue> items) {
        return new ArrayReply(items);
    }
}
