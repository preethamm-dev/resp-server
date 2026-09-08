package com.preetham.respserver.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Serialises {@link RespValue} back onto the wire.
 *
 * <p>Encoding is the easy direction: unlike parsing, there is never a question of
 * "do I have enough bytes yet" -- the whole value is in hand. The only subtlety is
 * that simple strings and errors are not length-prefixed, so a CR or LF inside one
 * would desynchronise the stream for the client. Those are rejected rather than
 * silently truncated.
 *
 * <p>Callers that are answering a pipelined batch should encode every reply into a
 * single stream and flush once. One write syscall for 100 replies rather than 100
 * is most of why pipelining is fast.
 */
public final class RespWriter {

    private static final byte[] CRLF = {'\r', '\n'};
    private static final byte[] NULL_BULK = "$-1\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NULL_ARRAY = "*-1\r\n".getBytes(StandardCharsets.US_ASCII);

    private RespWriter() {
    }

    /** Encodes a single value into a fresh byte array. Convenient for tests. */
    public static byte[] encode(RespValue value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64);
        try {
            write(value, out);
        } catch (IOException e) {
            // ByteArrayOutputStream does not perform I/O and cannot fail.
            throw new AssertionError("unreachable", e);
        }
        return out.toByteArray();
    }

    /** Writes one value. Does not flush -- the caller decides when to flush. */
    public static void write(RespValue value, OutputStream out) throws IOException {
        switch (value) {
            case RespValue.SimpleString(String s) -> {
                requireNoNewlines(s, "simple string");
                out.write('+');
                out.write(s.getBytes(StandardCharsets.UTF_8));
                out.write(CRLF);
            }
            case RespValue.ErrorReply(String message) -> {
                requireNoNewlines(message, "error message");
                out.write('-');
                out.write(message.getBytes(StandardCharsets.UTF_8));
                out.write(CRLF);
            }
            case RespValue.IntegerReply(long n) -> {
                out.write(':');
                writeLong(n, out);
                out.write(CRLF);
            }
            case RespValue.BulkString(byte[] bytes) -> {
                out.write('$');
                writeLong(bytes.length, out);
                out.write(CRLF);
                out.write(bytes);
                out.write(CRLF);
            }
            case RespValue.ArrayReply(var items) -> {
                out.write('*');
                writeLong(items.size(), out);
                out.write(CRLF);
                for (RespValue item : items) {
                    write(item, out);
                }
            }
            case RespValue.Null n -> out.write(n == RespValue.Null.BULK ? NULL_BULK : NULL_ARRAY);
        }
    }

    private static void writeLong(long value, OutputStream out) throws IOException {
        out.write(Long.toString(value).getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * Simple strings and errors are terminated by CRLF rather than length-prefixed,
     * so an embedded CR or LF would make the client read the remainder as a separate
     * reply and every subsequent reply would be off by one. Fail loudly instead --
     * any value that might contain arbitrary bytes belongs in a bulk string.
     */
    private static void requireNoNewlines(String s, String what) {
        if (s.indexOf('\r') >= 0 || s.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(
                    "a " + what + " may not contain CR or LF; use a bulk string instead");
        }
    }
}
