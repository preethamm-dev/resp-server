package com.preetham.respserver.protocol;

import java.nio.ByteBuffer;

/**
 * Per-connection outbound buffer for the non-blocking server.
 *
 * <h2>Why writes need a buffer at all</h2>
 *
 * On a blocking socket, {@code write()} does not return until every byte is handed over,
 * so the caller never thinks about it. A non-blocking socket instead returns <em>how many
 * bytes it took</em>, which can be fewer than offered -- or zero -- when the kernel's send
 * buffer is full. That happens whenever the client is slower at reading than the server is
 * at replying, which is normal rather than exceptional.
 *
 * <p>The event loop cannot wait for the socket to drain: blocking would stall every other
 * connection. So the unsent remainder is parked here, the loop asks the selector to tell
 * it when the socket is writable again, and it resumes then. That is the whole reason
 * {@code OP_WRITE} exists.
 *
 * <h2>Bounding it</h2>
 *
 * A client that stops reading but keeps sending commands would otherwise make this buffer
 * grow without limit until the server runs out of memory -- a slow-consumer denial of
 * service. {@code maxPending} caps it and the connection is dropped instead. Redis has the
 * same protection under the name {@code client-output-buffer-limit}.
 *
 * <p>Not thread safe: one buffer belongs to one connection, and in the event-loop server
 * exactly one thread ever touches it.
 */
public final class WriteBuffer {

    private static final int INITIAL_CAPACITY = 8 * 1024;

    /** Default ceiling on unsent bytes before a slow client is disconnected. */
    public static final int DEFAULT_MAX_PENDING = 64 * 1024 * 1024;

    private byte[] data = new byte[INITIAL_CAPACITY];
    private int start;
    private int end;
    private final int maxPending;

    public WriteBuffer() {
        this(DEFAULT_MAX_PENDING);
    }

    public WriteBuffer(int maxPending) {
        this.maxPending = maxPending;
    }

    /** Bytes queued but not yet accepted by the kernel. */
    public int pending() {
        return end - start;
    }

    public boolean isEmpty() {
        return start == end;
    }

    public boolean isOverLimit() {
        return pending() > maxPending;
    }

    /** Queues bytes for sending. */
    public void append(byte[] src) {
        append(src, 0, src.length);
    }

    public void append(byte[] src, int offset, int length) {
        ensureRoom(length);
        System.arraycopy(src, offset, data, end, length);
        end += length;
    }

    private void ensureRoom(int length) {
        if (end + length <= data.length) {
            return;
        }
        // Reclaim the already-sent prefix before growing; on a busy connection that is
        // usually enough on its own and avoids allocating at all.
        if (start > 0) {
            System.arraycopy(data, start, data, 0, pending());
            end -= start;
            start = 0;
        }
        if (end + length <= data.length) {
            return;
        }
        int required = end + length;
        int grown = Math.max(data.length * 2, required);
        byte[] bigger = new byte[grown];
        System.arraycopy(data, 0, bigger, 0, end);
        data = bigger;
    }

    /**
     * A view of the unsent bytes, ready to hand to {@code SocketChannel.write}.
     * The channel advances the buffer's position by however much it accepted; pass that
     * number to {@link #consume}.
     */
    public ByteBuffer forWriting() {
        return ByteBuffer.wrap(data, start, pending());
    }

    /** Marks {@code count} bytes as sent. */
    public void consume(int count) {
        start += count;
        if (start == end) {
            // Fully drained: reset rather than letting the offsets creep upward forever.
            start = 0;
            end = 0;
        }
    }

    /** Releases a large buffer back to a small one once the connection goes quiet. */
    public void compactIfOversized() {
        if (isEmpty() && data.length > INITIAL_CAPACITY) {
            data = new byte[INITIAL_CAPACITY];
        }
    }

    public int capacity() {
        return data.length;
    }
}
