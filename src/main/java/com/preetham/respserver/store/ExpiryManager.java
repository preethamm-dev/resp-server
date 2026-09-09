package com.preetham.respserver.store;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Background reaper for keys whose deadline has passed.
 *
 * <h2>Why lazy expiry alone is not enough</h2>
 *
 * {@link Database} already treats an expired key as absent and deletes it when something
 * touches it. That is correct but not sufficient: a key nobody reads again is never
 * touched, so its memory is never reclaimed. A cache that writes a million session keys
 * with a one-hour TTL and then stops reading them would hold all million forever. Lazy
 * expiry keeps the server <em>correct</em>; active expiry keeps it <em>bounded</em>.
 *
 * <h2>The algorithm</h2>
 *
 * This is Redis's adaptive approach. Each cycle:
 *
 * <ol>
 *   <li>examine a sample of {@value #SAMPLE_SIZE} keys;</li>
 *   <li>delete the expired ones;</li>
 *   <li>if more than a quarter of the sample was expired, go round again immediately,
 *       up to {@value #MAX_ROUNDS} times.</li>
 * </ol>
 *
 * <p>The feedback loop is the clever part. A quarter is the trigger because if that
 * fraction of a random sample has expired, a similar fraction of the whole keyspace
 * probably has too, and it is worth continuing. When few keys are expiring the cycle
 * stops after one cheap round. So the reaper costs almost nothing when there is nothing
 * to do, and works hard exactly when there is -- without ever scanning the full keyspace,
 * which on a large database would stall the server.
 *
 * <p>{@link #MAX_ROUNDS} caps the work per cycle. Without it, a keyspace where everything
 * expires at once would keep the loop running and starve real commands -- trading a
 * memory problem for a latency problem.
 *
 * <p>This is best effort by design. A key is <em>logically</em> gone the moment its
 * deadline passes, because every read path checks; the reaper only reclaims the memory,
 * so being late is a memory question, never a correctness one.
 */
public final class ExpiryManager implements AutoCloseable {

    /** Keys examined per round. Redis uses the same number. */
    static final int SAMPLE_SIZE = 20;

    /** Continue for another round while more than this fraction of a sample was expired. */
    static final double CONTINUE_THRESHOLD = 0.25;

    /** Hard cap on rounds per cycle, so the reaper cannot monopolise the server. */
    static final int MAX_ROUNDS = 16;

    public static final Duration DEFAULT_INTERVAL = Duration.ofMillis(100);

    private final Database database;
    private final LongAdder keysReaped = new LongAdder();
    private final LongAdder cyclesRun = new LongAdder();
    private ScheduledExecutorService scheduler;

    public ExpiryManager(Database database) {
        this.database = database;
    }

    /** Starts the periodic cycle. */
    public void start(Duration interval) {
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            // A daemon thread, so a forgotten server does not keep the JVM alive.
            Thread thread = new Thread(runnable, "resp-expiry");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(
                this::runCycleSafely,
                interval.toMillis(),
                interval.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    /**
     * scheduleWithFixedDelay silently cancels the task if it ever throws, which would
     * stop expiry for the lifetime of the process with no indication why. Swallowing and
     * logging keeps the reaper alive.
     */
    private void runCycleSafely() {
        try {
            runCycle();
        } catch (RuntimeException e) {
            System.err.println("[resp-server] expiry cycle failed: " + e);
        }
    }

    /**
     * Runs one adaptive cycle. Exposed so tests can drive it deterministically instead of
     * waiting for the scheduler.
     *
     * @return how many keys were reclaimed
     */
    public int runCycle() {
        cyclesRun.increment();
        int reaped = 0;

        for (int round = 0; round < MAX_ROUNDS; round++) {
            List<String> sample = database.sampleKeysForExpiry(SAMPLE_SIZE);
            if (sample.isEmpty()) {
                break;
            }

            int expiredInRound = 0;
            for (String key : sample) {
                if (database.reapIfExpired(key)) {
                    expiredInRound++;
                }
            }
            reaped += expiredInRound;

            // Stop as soon as the sample looks mostly live; keep going while it does not.
            if (expiredInRound < sample.size() * CONTINUE_THRESHOLD) {
                break;
            }
        }

        keysReaped.add(reaped);
        return reaped;
    }

    public long keysReaped() {
        return keysReaped.sum();
    }

    public long cyclesRun() {
        return cyclesRun.sum();
    }

    @Override
    public void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }
}
