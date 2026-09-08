package com.preetham.respserver.stats;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Counters backing the {@code INFO} command.
 *
 * <p>{@link LongAdder} rather than {@link java.util.concurrent.atomic.AtomicLong}:
 * these counters are incremented on every command from every connection, and an
 * {@code AtomicLong} would make that single cache line the hottest contended address
 * in the server. {@code LongAdder} spreads the increments across per-thread cells and
 * only sums them when read, which is exactly the right trade for
 * write-often/read-rarely counters.
 *
 * <p>{@code connectedClients} stays an {@link AtomicInteger} because it goes both up
 * and down and is read as a live gauge, which {@code LongAdder} does not suit.
 */
public final class ServerStats {

    private final long startNanos = System.nanoTime();
    private final LongAdder totalConnections = new LongAdder();
    private final LongAdder totalCommands = new LongAdder();
    private final LongAdder rejectedConnections = new LongAdder();
    private final LongAdder protocolErrors = new LongAdder();
    private final AtomicInteger connectedClients = new AtomicInteger();

    public void connectionOpened() {
        totalConnections.increment();
        connectedClients.incrementAndGet();
    }

    public void connectionClosed() {
        connectedClients.decrementAndGet();
    }

    public void connectionRejected() {
        rejectedConnections.increment();
    }

    public void commandProcessed() {
        totalCommands.increment();
    }

    public void protocolError() {
        protocolErrors.increment();
    }

    public long totalConnections() {
        return totalConnections.sum();
    }

    public long totalCommands() {
        return totalCommands.sum();
    }

    public long rejectedConnections() {
        return rejectedConnections.sum();
    }

    public long protocolErrors() {
        return protocolErrors.sum();
    }

    public int connectedClients() {
        return connectedClients.get();
    }

    public long uptimeSeconds() {
        return Duration.ofNanos(System.nanoTime() - startNanos).toSeconds();
    }
}
