package com.preetham.respserver.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Parses RESP2 frames out of a byte buffer, one complete value at a time.
 *
 * <h2>Why this is incremental</h2>
 *
 * TCP is a stream of bytes, not a stream of messages. A single {@code read()} can
 * return half of {@code SET foo bar}, or two and a half commands, or one byte. A
 * parser that assumes "one read == one command" is the most common bug in
 * hand-written network code.
 *
 * <p>So {@link #tryParse} never blocks and never guesses. It either consumes
 * exactly one complete value and advances the buffer past it, or it leaves the
 * buffer <em>exactly as it found it</em> and returns empty, meaning "come back
 * when you have more bytes". Only genuinely impossible input throws.
 *
 * <p>Building this on day one is what makes both the blocking server and the
 * non-blocking event loop possible from the same code, and it is why pipelining
 * works for free: if a client sends 100 commands in one write, the caller simply
 * loops until {@code tryParse} returns empty.
 *
 * <h2>Buffer contract</h2>
 *
 * The buffer is in read mode: {@code position} is the first unparsed byte and
 * {@code limit} is the end of received data.
 * <ul>
 *   <li>complete value  -- returns it, position advanced past the frame</li>
 *   <li>incomplete      -- returns empty, position restored to where it started</li>
 *   <li>malformed       -- throws; the caller should close the connection</li>
 * </ul>
 *
 * <p>This class is stateless and therefore safe to share between threads. All
 * per-connection state lives in the buffer owned by the connection.
 */
public final class RespReader {

    /** Redis caps a bulk string at 512 MB; anything larger is a protocol error. */
    public static final int DEFAULT_MAX_BULK_LENGTH = 512 * 1024 * 1024;

    /** Guards against a hostile {@code *2000000000\r\n} header causing a huge allocation. */
    public static final int DEFAULT_MAX_ARRAY_SIZE = 1024 * 1024;

    /** Matches Redis's inline/protocol line limit. Stops an endless line eating memory. */
    public static final int MAX_LINE_LENGTH = 64 * 1024;

    /** Bounds recursion so deeply nested arrays cannot blow the stack. */
    private static final int MAX_DEPTH = 32;

    private final int maxBulkLength;
    private final int maxArraySize;
    private final boolean allowInline;

    private RespReader(int maxBulkLength, int maxArraySize, boolean allowInline) {
        this.maxBulkLength = maxBulkLength;
        this.maxArraySize = maxArraySize;
        this.allowInline = allowInline;
    }

    /**
     * Reader for the server side. Accepts inline commands -- a bare line such as
     * {@code PING\r\n} typed into telnet or netcat -- in addition to RESP arrays.
     * Real clients always send arrays, but Redis supports inline and it makes the
     * server trivially pokeable without a client.
     */
    public static RespReader forServer() {
        return new RespReader(DEFAULT_MAX_BULK_LENGTH, DEFAULT_MAX_ARRAY_SIZE, true);
    }

    /** Strict RESP only, no inline fallback. Used for parsing replies and in codec tests. */
    public static RespReader strict() {
        return new RespReader(DEFAULT_MAX_BULK_LENGTH, DEFAULT_MAX_ARRAY_SIZE, false);
    }

    /** Reader with tightened limits, used by fuzz and resource-exhaustion tests. */
    public static RespReader withLimits(int maxBulkLength, int maxArraySize, boolean allowInline) {
        return new RespReader(maxBulkLength, maxArraySize, allowInline);
    }

    /**
     * Attempts to parse exactly one value.
     *
     * @return the value, or empty if the buffer does not yet hold a complete frame
     * @throws ProtocolException if the bytes cannot become a valid frame
     */
    public Optional<RespValue> tryParse(ByteBuffer buf) throws ProtocolException {
        int start = buf.position();
        Optional<RespValue> parsed = parseValue(buf, 0);
        if (parsed.isEmpty()) {
            // Incomplete: rewind so the next attempt sees the frame from its start.
            buf.position(start);
        }
        return parsed;
    }

    private Optional<RespValue> parseValue(ByteBuffer buf, int depth) throws ProtocolException {
        if (depth > MAX_DEPTH) {
            throw new ProtocolException("nesting too deep (max " + MAX_DEPTH + ")");
        }
        if (!buf.hasRemaining()) {
            return Optional.empty();
        }

        int typePosition = buf.position();
        byte type = buf.get();

        return switch (type) {
            case '+' -> readLine(buf).map(RespValue::simple);
            case '-' -> readLine(buf).map(RespValue::error);
            case ':' -> {
                Optional<String> line = readLine(buf);
                if (line.isEmpty()) {
                    yield Optional.empty();
                }
                yield Optional.of(RespValue.integer(parseLong(line.get())));
            }
            case '$' -> parseBulkString(buf);
            case '*' -> parseArray(buf, depth);
            default -> {
                if (allowInline && depth == 0) {
                    // Not a RESP type byte: treat the whole line as an inline command.
                    buf.position(typePosition);
                    yield parseInline(buf);
                }
                throw new ProtocolException(
                        "unexpected byte '" + (char) type + "' (0x"
                                + Integer.toHexString(type & 0xFF) + ") at start of value");
            }
        };
    }

    private Optional<RespValue> parseBulkString(ByteBuffer buf) throws ProtocolException {
        Optional<String> header = readLine(buf);
        if (header.isEmpty()) {
            return Optional.empty();
        }
        long length = parseLong(header.get());

        if (length == -1) {
            return Optional.of(RespValue.Null.BULK);
        }
        if (length < 0) {
            throw new ProtocolException("invalid bulk length: " + length);
        }
        if (length > maxBulkLength) {
            throw new ProtocolException(
                    "bulk length " + length + " exceeds limit of " + maxBulkLength);
        }

        // The payload is followed by CRLF, so we need length + 2 bytes.
        int needed = (int) length + 2;
        if (buf.remaining() < needed) {
            return Optional.empty();
        }

        byte[] data = new byte[(int) length];
        buf.get(data);

        if (buf.get() != '\r' || buf.get() != '\n') {
            throw new ProtocolException("bulk string not terminated by CRLF");
        }
        return Optional.of(RespValue.bulk(data));
    }

    private Optional<RespValue> parseArray(ByteBuffer buf, int depth) throws ProtocolException {
        Optional<String> header = readLine(buf);
        if (header.isEmpty()) {
            return Optional.empty();
        }
        long count = parseLong(header.get());

        if (count == -1) {
            return Optional.of(RespValue.Null.ARRAY);
        }
        if (count < 0) {
            throw new ProtocolException("invalid array length: " + count);
        }
        if (count > maxArraySize) {
            throw new ProtocolException(
                    "array length " + count + " exceeds limit of " + maxArraySize);
        }

        List<RespValue> items = new ArrayList<>((int) count);
        for (long i = 0; i < count; i++) {
            Optional<RespValue> item = parseValue(buf, depth + 1);
            if (item.isEmpty()) {
                // A later element is incomplete. tryParse rewinds the whole frame,
                // so the partial work here is simply discarded and retried.
                return Optional.empty();
            }
            items.add(item.get());
        }
        return Optional.of(RespValue.array(items));
    }

    /**
     * Parses an inline command: a plain line of whitespace-separated words, as
     * produced by typing {@code PING} into telnet. Represented as an array of bulk
     * strings so the rest of the server cannot tell the difference.
     */
    private Optional<RespValue> parseInline(ByteBuffer buf) throws ProtocolException {
        Optional<String> line = readInlineLine(buf);
        if (line.isEmpty()) {
            return Optional.empty();
        }
        String trimmed = line.get().trim();
        if (trimmed.isEmpty()) {
            return Optional.of(RespValue.EMPTY_ARRAY);
        }
        List<RespValue> words = new ArrayList<>();
        for (String word : trimmed.split("\\s+")) {
            words.add(RespValue.bulk(word));
        }
        return Optional.of(RespValue.array(words));
    }

    /**
     * Reads up to the next CRLF. On success the position lands just past the CRLF;
     * on failure the position is untouched and the caller returns empty.
     */
    private static Optional<String> readLine(ByteBuffer buf) throws ProtocolException {
        int start = buf.position();
        int limit = buf.limit();

        for (int i = start; i < limit - 1; i++) {
            if (buf.get(i) == '\r' && buf.get(i + 1) == '\n') {
                int length = i - start;
                byte[] line = new byte[length];
                buf.get(start, line, 0, length);
                buf.position(i + 2);
                return Optional.of(new String(line, StandardCharsets.US_ASCII));
            }
        }

        // No terminator yet. If the unterminated run is already absurdly long the
        // peer is not speaking RESP, and waiting for more would just consume memory.
        if (limit - start > MAX_LINE_LENGTH) {
            throw new ProtocolException("line exceeds " + MAX_LINE_LENGTH + " bytes without CRLF");
        }
        return Optional.empty();
    }

    /**
     * Reads an inline command line, terminated by LF with an optional preceding CR.
     *
     * <p>Inline commands are deliberately more forgiving than RESP frames. A RESP frame is
     * machine-generated and the specification mandates CRLF, so accepting anything else
     * there would mask genuine corruption. An inline command is typed by a human or
     * produced by a shell, and neither reliably emits CR -- {@code echo "PING"} sends a
     * bare LF, as do telnet on most platforms and anything piped from a script. Real Redis
     * splits inline input on LF and strips a trailing CR if present; requiring CRLF makes
     * the server look broken to exactly the casual client that inline mode exists for.
     */
    private static Optional<String> readInlineLine(ByteBuffer buf) throws ProtocolException {
        int start = buf.position();
        int limit = buf.limit();

        for (int i = start; i < limit; i++) {
            if (buf.get(i) == '\n') {
                int end = (i > start && buf.get(i - 1) == '\r') ? i - 1 : i;
                int length = end - start;
                byte[] line = new byte[length];
                buf.get(start, line, 0, length);
                buf.position(i + 1);
                return Optional.of(new String(line, StandardCharsets.US_ASCII));
            }
        }

        if (limit - start > MAX_LINE_LENGTH) {
            throw new ProtocolException(
                    "inline command exceeds " + MAX_LINE_LENGTH + " bytes without a newline");
        }
        return Optional.empty();
    }

    private static long parseLong(String s) throws ProtocolException {
        if (s.isEmpty()) {
            throw new ProtocolException("expected a number but the line was empty");
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw new ProtocolException("invalid number: '" + s + "'");
        }
    }
}
