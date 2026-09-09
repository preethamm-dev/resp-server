package com.preetham.respserver.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Active expiry: the background reaper that reclaims memory lazy expiry would never touch.
 *
 * <p>Most of these drive {@link ExpiryManager#runCycle()} directly rather than starting the
 * scheduler. A test that waits for a timer is slow and, worse, flaky on a loaded machine.
 * Calling the cycle by hand makes the adaptive behaviour observable and the assertions
 * exact. One test does start the scheduler, because "the timer is actually wired up" is a
 * separate claim from "the algorithm is right".
 */
class ExpiryManagerTest {

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final Database db = new Database(now::get);
    private final ExpiryManager expiry = new ExpiryManager(db);

    private void advance(long millis) {
        now.addAndGet(millis);
    }

    @Test
    @DisplayName("keys nobody reads are still reclaimed")
    void reclaimsExpiredKeysWithoutAnyoneTouchingThem() {
        for (int i = 0; i < 50; i++) {
            db.set("k" + i, RedisString.of("v"), now.get() + 100);
        }
        assertThat(db.rawSize()).isEqualTo(50);

        advance(100);

        // Lazy expiry alone would leave all 50 in memory: nothing ever reads them.
        assertThat(db.rawSize()).as("still occupying memory before the reaper runs").isEqualTo(50);

        for (int cycle = 0; cycle < 10; cycle++) {
            expiry.runCycle();
        }

        assertThat(db.rawSize()).isZero();
        assertThat(expiry.keysReaped()).isEqualTo(50);
    }

    @Test
    void leavesLiveKeysAlone() {
        db.set("permanent", RedisString.of("v"));
        db.set("long-lived", RedisString.of("v"), now.get() + 100_000);
        db.set("doomed", RedisString.of("v"), now.get() + 10);

        advance(10);
        for (int cycle = 0; cycle < 5; cycle++) {
            expiry.runCycle();
        }

        assertThat(db.exists("permanent")).isTrue();
        assertThat(db.exists("long-lived")).isTrue();
        assertThat(db.exists("doomed")).isFalse();
        assertThat(expiry.keysReaped()).isEqualTo(1);
    }

    @Test
    @DisplayName("a cycle over a healthy keyspace stops after one cheap round")
    void doesLittleWorkWhenNothingIsExpiring() {
        for (int i = 0; i < 500; i++) {
            db.set("k" + i, RedisString.of("v"));
        }

        int reaped = expiry.runCycle();

        assertThat(reaped).isZero();
        assertThat(db.rawSize()).isEqualTo(500);
    }

    @Test
    @DisplayName("the sampling cursor eventually covers the whole keyspace")
    void cursorWrapsAroundSoEveryKeyIsEventuallyExamined() {
        // Far more keys than one sample, so a cursor that never advanced would only ever
        // reap the first 20.
        for (int i = 0; i < 200; i++) {
            db.set("k" + i, RedisString.of("v"), now.get() + 10);
        }
        advance(10);

        int cycles = 0;
        while (db.rawSize() > 0 && cycles < 100) {
            expiry.runCycle();
            cycles++;
        }

        assertThat(db.rawSize()).as("every key was reached within %d cycles", cycles).isZero();
    }

    @Test
    @DisplayName("one cycle is capped, so the reaper cannot monopolise the server")
    void cycleWorkIsBounded() {
        for (int i = 0; i < 10_000; i++) {
            db.set("k" + i, RedisString.of("v"), now.get() + 10);
        }
        advance(10);

        int reaped = expiry.runCycle();

        // Every round finds a fully expired sample, so the cycle runs the maximum number
        // of rounds and then stops -- it does not keep going until the keyspace is clear.
        assertThat(reaped).isEqualTo(ExpiryManager.MAX_ROUNDS * ExpiryManager.SAMPLE_SIZE);
        assertThat(db.rawSize()).isGreaterThan(0);
    }

    @Test
    @DisplayName("the scheduler actually runs cycles")
    void scheduledCyclesRunWithoutBeingCalledByHand() {
        // Real wall-clock time here, since this test is about the timer rather than the
        // algorithm.
        Database realClockDb = new Database();
        ExpiryManager scheduled = new ExpiryManager(realClockDb);

        for (int i = 0; i < 30; i++) {
            realClockDb.set("k" + i, RedisString.of("v"), System.currentTimeMillis() + 50);
        }

        try {
            scheduled.start(Duration.ofMillis(20));

            Awaitility.await()
                    .atMost(Duration.ofSeconds(10))
                    .pollInterval(Duration.ofMillis(20))
                    .untilAsserted(() -> assertThat(realClockDb.rawSize()).isZero());
        } finally {
            scheduled.close();
        }

        assertThat(scheduled.cyclesRun()).isPositive();
    }
}
