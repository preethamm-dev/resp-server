package com.preetham.respserver.stats;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

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

    /**
     * Descriptive server state for {@code INFO}, wired up at startup.
     *
     * <p>Held as suppliers rather than copied values so {@code INFO} always reports live
     * counters. Making {@code ServerStats} depend on {@code ExpiryManager} and
     * {@code AofWriter} directly would couple the counters to two subsystems that may not
     * exist -- persistence is optional -- and would drag their packages into everything
     * that touches statistics.
     */
    private volatile String mode = "unknown";
    private volatile String persistence = "disabled";
    private volatile LongSupplier expiredKeys = () -> 0L;
    private volatile LongSupplier expiryCycles = () -> 0L;
    private volatile LongSupplier aofCommands = () -> 0L;
    private volatile LongSupplier aofSyncs = () -> 0L;

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

    // ---- descriptive state, wired at startup -----------------------------------

    public void describeMode(String mode) {
        this.mode = mode;
    }

    public void describePersistence(String persistence) {
        this.persistence = persistence;
    }

    public void trackExpiry(LongSupplier keysReaped, LongSupplier cycles) {
        this.expiredKeys = keysReaped;
        this.expiryCycles = cycles;
    }

    public void trackAof(LongSupplier commandsAppended, LongSupplier syncs) {
        this.aofCommands = commandsAppended;
        this.aofSyncs = syncs;
    }

    public String mode() {
        return mode;
    }

    public String persistence() {
        return persistence;
    }

    public long expiredKeys() {
        return expiredKeys.getAsLong();
    }

    public long expiryCycles() {
        return expiryCycles.getAsLong();
    }

    public long aofCommands() {
        return aofCommands.getAsLong();
    }

    public long aofSyncs() {
        return aofSyncs.getAsLong();
    }
}
