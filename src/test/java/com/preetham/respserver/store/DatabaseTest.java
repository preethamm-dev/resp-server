package com.preetham.respserver.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Keyspace semantics: expiry, type discipline, and atomicity.
 *
 * <p>Expiry is driven by a fake clock rather than {@code Thread.sleep}. Sleeping would
 * make the suite slow and, worse, flaky -- a loaded CI machine can overshoot a 50ms
 * sleep by enough to change the outcome. Moving a counter forward is instant and exact.
 */
class DatabaseTest {

    /** A clock the test moves by hand. */
    private final AtomicLong now = new AtomicLong(1_000_000);
    private final Database db = new Database(now::get);

    private void advance(long millis) {
        now.addAndGet(millis);
    }

    @Nested
    @DisplayName("basic reads and writes")
    class Basics {

        @Test
        void storesAndReadsBack() {
            db.set("k", RedisString.of("v"));

            assertThat(db.getString("k")).contains(RedisString.of("v"));
            assertThat(db.exists("k")).isTrue();
            assertThat(db.type("k")).contains("string");
        }

        @Test
        void missingKeyReadsAsEmpty() {
            assertThat(db.getString("nope")).isEmpty();
            assertThat(db.exists("nope")).isFalse();
            assertThat(db.type("nope")).isEmpty();
        }

        @Test
        void deleteReportsWhetherSomethingWasRemoved() {
            db.set("k", RedisString.of("v"));

            assertThat(db.delete("k")).isTrue();
            assertThat(db.delete("k")).as("already gone").isFalse();
        }

        @Test
        void deleteCountsOnlyKeysThatExisted() {
            db.set("a", RedisString.of("1"));
            db.set("b", RedisString.of("2"));

            assertThat(db.delete(List.of("a", "b", "missing"))).isEqualTo(2);
        }

        @Test
        void flushAllEmptiesTheKeyspace() {
            db.set("a", RedisString.of("1"));
            db.set("b", RedisString.of("2"));

            db.flushAll();

            assertThat(db.size()).isZero();
        }

        @Test
        void valuesAreBinarySafe() {
            byte[] awkward = {0, '\r', '\n', (byte) 0xFF, 'x'};
            db.set("bin", new RedisString(awkward));

            assertThat(db.getString("bin").orElseThrow().value()).isEqualTo(awkward);
        }
    }

    @Nested
    @DisplayName("expiry")
    class Expiry {

        @Test
        void keyDisappearsOnceItsDeadlinePasses() {
            db.set("k", RedisString.of("v"), now.get() + 100);

            assertThat(db.getString("k")).isPresent();

            advance(100);

            assertThat(db.getString("k")).as("expired keys read as absent").isEmpty();
            assertThat(db.exists("k")).isFalse();
        }

        @Test
        @DisplayName("expiry is inclusive: a key is gone at its deadline, not one tick after")
        void expiresExactlyAtTheDeadline() {
            db.set("k", RedisString.of("v"), now.get() + 100);

            advance(99);
            assertThat(db.exists("k")).isTrue();

            advance(1);
            assertThat(db.exists("k")).isFalse();
        }

        @Test
        void ttlReportsTheThreeDistinctStates() {
            assertThat(db.ttlMillis("missing")).isEqualTo(Database.TTL_KEY_NOT_FOUND);

            db.set("persistent", RedisString.of("v"));
            assertThat(db.ttlMillis("persistent")).isEqualTo(Database.TTL_NO_EXPIRY);

            db.set("volatile", RedisString.of("v"), now.get() + 5000);
            assertThat(db.ttlMillis("volatile")).isEqualTo(5000);
        }

        @Test
        void expireAtOnlyAppliesToExistingKeys() {
            assertThat(db.expireAt("missing", now.get() + 1000)).isFalse();

            db.set("k", RedisString.of("v"));
            assertThat(db.expireAt("k", now.get() + 1000)).isTrue();
            assertThat(db.ttlMillis("k")).isEqualTo(1000);
        }

        @Test
        void persistClearsTheDeadlineAndReportsWhetherItChangedAnything() {
            db.set("k", RedisString.of("v"), now.get() + 1000);

            assertThat(db.persist("k")).isTrue();
            assertThat(db.ttlMillis("k")).isEqualTo(Database.TTL_NO_EXPIRY);
            assertThat(db.persist("k")).as("nothing left to clear").isFalse();
            assertThat(db.persist("missing")).isFalse();
        }

        @Test
        @DisplayName("a plain SET clears any existing TTL")
        void plainSetDropsTheExistingExpiry() {
            db.set("k", RedisString.of("first"), now.get() + 1000);

            db.set("k", RedisString.of("second"));

            assertThat(db.ttlMillis("k")).isEqualTo(Database.TTL_NO_EXPIRY);
        }

        @Test
        @DisplayName("INCR keeps the TTL -- a counter must not become immortal")
        void incrPreservesExpiry() {
            db.set("hits", RedisString.of("1"), now.get() + 1000);

            db.incrBy("hits", 1);

            assertThat(db.ttlMillis("hits")).isEqualTo(1000);
        }

        @Test
        void sizeAndKeysIgnoreExpiredEntries() {
            db.set("live", RedisString.of("v"));
            db.set("doomed", RedisString.of("v"), now.get() + 10);

            assertThat(db.size()).isEqualTo(2);

            advance(10);

            assertThat(db.size()).isEqualTo(1);
            assertThat(db.keys("*")).containsExactly("live");
        }

        @Test
        @DisplayName("setting over an expired key behaves as if the key were absent")
        void nxSucceedsOverAnExpiredKey() {
            db.set("k", RedisString.of("old"), now.get() + 10);
            advance(10);

            assertThat(db.setIfAbsent("k", RedisString.of("new"), ValueHolder.NO_EXPIRY)).isTrue();
            assertThat(db.getString("k")).contains(RedisString.of("new"));
        }
    }

    @Nested
    @DisplayName("conditional writes")
    class Conditional {

        @Test
        void nxOnlyWritesWhenAbsent() {
            assertThat(db.setIfAbsent("k", RedisString.of("first"), ValueHolder.NO_EXPIRY)).isTrue();
            assertThat(db.setIfAbsent("k", RedisString.of("second"), ValueHolder.NO_EXPIRY)).isFalse();

            assertThat(db.getString("k")).contains(RedisString.of("first"));
        }

        @Test
        void xxOnlyWritesWhenPresent() {
            assertThat(db.setIfPresent("k", RedisString.of("v"), ValueHolder.NO_EXPIRY)).isFalse();
            assertThat(db.exists("k")).as("a failed XX must not create the key").isFalse();

            db.set("k", RedisString.of("original"));

            assertThat(db.setIfPresent("k", RedisString.of("updated"), ValueHolder.NO_EXPIRY)).isTrue();
            assertThat(db.getString("k")).contains(RedisString.of("updated"));
        }
    }

    @Nested
    @DisplayName("numeric operations")
    class Numeric {

        @Test
        void incrTreatsAMissingKeyAsZero() {
            assertThat(db.incrBy("counter", 1)).isEqualTo(1);
            assertThat(db.incrBy("counter", 5)).isEqualTo(6);
            assertThat(db.incrBy("counter", -10)).isEqualTo(-4);
        }

        @Test
        void incrRejectsNonNumericValues() {
            db.set("k", RedisString.of("abc"));

            assertThatThrownBy(() -> db.incrBy("k", 1))
                    .isInstanceOf(NotAnIntegerException.class);
        }

        @Test
        @DisplayName("non-canonical numbers are rejected because INCR would rewrite them")
        void incrRejectsNonCanonicalNumbers() {
            for (String bad : List.of(" 1", "1 ", "1.0", "007", "+1", "")) {
                Database fresh = new Database(now::get);
                fresh.set("k", RedisString.of(bad));

                assertThatThrownBy(() -> fresh.incrBy("k", 1))
                        .as("value %s", "'" + bad + "'")
                        .isInstanceOf(NotAnIntegerException.class);
            }
        }

        @Test
        void incrRefusesToOverflowRatherThanWrapping() {
            db.set("k", RedisString.of(Long.MAX_VALUE));

            assertThatThrownBy(() -> db.incrBy("k", 1))
                    .isInstanceOf(RedisDataException.class)
                    .hasMessageContaining("overflow");
        }

        @Test
        void appendReturnsTheNewLengthAndStartsFromEmpty() {
            assertThat(db.append("k", "abc".getBytes())).isEqualTo(3);
            assertThat(db.append("k", "de".getBytes())).isEqualTo(5);
            assertThat(db.getString("k")).contains(RedisString.of("abcde"));
        }
    }

    @Nested
    @DisplayName("type discipline")
    class Types {

        /**
         * The interesting case -- {@code INCR} against a list, {@code LPUSH} against a
         * string -- cannot be written yet: {@code RedisString} is currently the only
         * permitted subtype of the sealed {@link RedisObject}, so there is no second
         * type to mismatch against. A test double will not do either, precisely because
         * sealing forbids one. These assertions therefore pin down the error contract
         * the store must honour, and the behavioural test arrives with the list, hash
         * and set types.
         */
        @Test
        void wrongTypeUsesRedisOwnErrorCodeNotTheGenericOne() {
            assertThat(new WrongTypeException().respError()).startsWith("WRONGTYPE ");
            assertThat(new NotAnIntegerException().respError()).startsWith("ERR ");
        }

        @Test
        void typeNameIsReportedForTheTypeCommand() {
            db.set("k", RedisString.of("v"));

            assertThat(db.type("k")).contains("string");
        }
    }

    @Nested
    @DisplayName("concurrency")
    class Concurrency {

        @Test
        @DisplayName("100 virtual threads incrementing one key lose no updates")
        void incrementsAreAtomicUnderContention() throws Exception {
            int threads = 100;
            int perThread = 1000;
            Database shared = new Database();

            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);

            try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int t = 0; t < threads; t++) {
                    pool.submit(() -> {
                        try {
                            start.await();
                            for (int i = 0; i < perThread; i++) {
                                shared.incrBy("counter", 1);
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
                }

                start.countDown(); // release everyone at once to maximise contention
                assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
            }

            // A get-then-set implementation would lose updates here and land short.
            assertThat(shared.getString("counter").orElseThrow().asLong())
                    .isEqualTo((long) threads * perThread);
        }

        @Test
        @DisplayName("concurrent appends never interleave into a corrupt value")
        void appendsAreAtomic() throws Exception {
            int threads = 50;
            Database shared = new Database();
            CountDownLatch done = new CountDownLatch(threads);

            try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int t = 0; t < threads; t++) {
                    pool.submit(() -> {
                        try {
                            for (int i = 0; i < 100; i++) {
                                shared.append("log", "x".getBytes());
                            }
                        } finally {
                            done.countDown();
                        }
                    });
                }
                assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
            }

            assertThat(shared.getString("log").orElseThrow().length()).isEqualTo(threads * 100);
        }
    }
}
