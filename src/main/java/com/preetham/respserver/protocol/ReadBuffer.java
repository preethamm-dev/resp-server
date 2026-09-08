package com.preetham.respserver.protocol;

import java.nio.ByteBuffer;

/**
 * Per-connection accumulation buffer: the bridge between "bytes arrived" and
 * "a complete command is available".
 *
 * <p>Because a RESP frame can be split across any number of reads, each connection has
 * to hold on to the bytes it has seen so far. The cycle is always the same:
 *
 * <pre>
 *   append(what just arrived)
 *   loop:  parse a complete value out of forParsing(), if there is one
 *   consume(how many bytes the parser actually used)
 * </pre>
 *
 * <p>{@link #consume} compacts by copying the unparsed tail back to the front. That is
 * an O(remaining) memcpy, but "remaining" is at most one partial command, so it is a
 * handful of bytes in practice -- and it keeps the buffer from growing without bound
 * on a long-lived connection.
 *
 * <p>{@code maxCapacity} matters for more than tidiness. Without it, a client that
 * opens a connection and sends {@code $1073741824\r\n} would make the server buffer a
 * gigabyte waiting for a payload that never comes. Refusing to grow past the cap turns
 * a memory-exhaustion attack into a closed connection.
 *
 * <p>Not thread safe: a buffer belongs to exactly one connection, which is handled by
 * exactly one thread at a time.
 */
public final class ReadBuffer {

    private static final int INITIAL_CAPACITY = 16 * 1024;

    /** Enough for the largest frame the reader will accept, plus protocol overhead. */
    public static final int DEFAULT_MAX_CAPACITY = RespReader.DEFAULT_MAX_BULK_LENGTH + 1024;

    private byte[] data;
    private int length;
    private final int maxCapacity;

    public ReadBuffer() {
        this(DEFAULT_MAX_CAPACITY);
    }

    public ReadBuffer(int maxCapacity) {
        this.data = new byte[Math.min(INITIAL_CAPACITY, maxCapacity)];
        this.length = 0;
        this.maxCapacity = maxCapacity;
    }

    /**
     * Appends freshly read bytes, growing the backing array if needed.
     *
     * @throws ProtocolException if the buffer would exceed its capacity limit
     */
    public void append(byte[] src, int offset, int count) throws ProtocolException {
        ensureCapacity(length + count);
        System.arraycopy(src, offset, data, length, count);
        length += count;
    }

    /** Appends whatever remains in {@code src}. Used by the NIO path. */
    public void append(ByteBuffer src) throws ProtocolException {
        int count = src.remaining();
        ensureCapacity(length + count);
        src.get(data, length, count);
        length += count;
    }

    private void ensureCapacity(int required) throws ProtocolException {
        if (required <= data.length) {
            return;
        }
        if (required > maxCapacity) {
            throw new ProtocolException(
                    "inbound buffer would exceed " + maxCapacity + " bytes");
        }
        int grown = Math.max(data.length * 2, required);
        byte[] bigger = new byte[Math.min(grown, maxCapacity)];
        System.arraycopy(data, 0, bigger, 0, length);
        data = bigger;
    }

    /**
     * A view of the unparsed bytes, ready to hand to
     * {@link RespReader#tryParse(ByteBuffer)}.
     *
     * <p>The returned buffer shares this object's array, so the parser's position
     * directly reports how much it consumed -- pass that to {@link #consume}.
     */
    public ByteBuffer forParsing() {
        return ByteBuffer.wrap(data, 0, length);
    }

    /** Discards the first {@code count} bytes, keeping any partial frame after them. */
    public void consume(int count) {
        if (count <= 0) {
            return;
        }
        if (count >= length) {
            length = 0;
            return;
        }
        System.arraycopy(data, count, data, 0, length - count);
        length -= count;
    }

    public int length() {
        return length;
    }

    public boolean isEmpty() {
        return length == 0;
    }

    public int capacity() {
        return data.length;
    }
}
