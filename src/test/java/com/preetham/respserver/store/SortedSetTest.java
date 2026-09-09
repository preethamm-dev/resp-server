package com.preetham.respserver.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/**
 * Tests the skip list and the sorted set built on it.
 *
 * <h2>Why an oracle rather than hand-written expectations</h2>
 *
 * A skip list is randomised: no two runs build the same shape, so a fixed sequence of
 * inserts exercises a different structure each time and a bug can hide for a hundred
 * runs before a particular set of coin flips exposes it.
 *
 * <p>So instead of asserting specific outputs, these tests run the same random operations
 * against a {@link TreeSet} -- a structure whose correctness is not in question -- and
 * require the two to agree on order, membership and, crucially, <b>rank</b>.
 *
 * <p>Rank is what makes the oracle worth having. A span bug does not break ordering:
 * iteration still returns everything correctly, {@code ZRANGE} looks fine, and only
 * {@code ZRANK} quietly returns wrong numbers. Comparing against an oracle that computes
 * rank by counting catches exactly that class of error.
 *
 * <p>{@code checkInvariants} adds a second, independent line of defence by verifying every
 * span really equals the distance it claims to jump, so a violation is caught at the
 * operation that caused it rather than in a later query.
 */
class SortedSetTest {

    /** Redis orders by score, then lexicographically by member. */
    private static final Comparator<ScoredMember> ORDER =
            Comparator.<ScoredMember>comparingDouble(ScoredMember::score)
                    .thenComparing(ScoredMember::member);

    private static Bytes member(String s) {
        return Bytes.of(s);
    }

    @Nested
    @DisplayName("basic behaviour")
    class Basics {

        @Test
        void ordersByScoreThenByMember() {
            RedisSortedSet zset = new RedisSortedSet();
            zset.add(member("charlie"), 2);
            zset.add(member("alice"), 1);
            zset.add(member("bob"), 2);

            assertThat(zset.snapshot())
                    .extracting(entry -> entry.member().asString())
                    .containsExactly("alice", "bob", "charlie");
        }

        @Test
        void addReportsOnlyNewMembers() {
            RedisSortedSet zset = new RedisSortedSet();

            assertThat(zset.add(member("a"), 1)).isTrue();
            assertThat(zset.add(member("a"), 2)).as("updating a score is not an addition").isFalse();
            assertThat(zset.size()).isEqualTo(1);
            assertThat(zset.score(member("a"))).isEqualTo(2.0);
        }

        @Test
        @DisplayName("changing a score moves the member to its new position")
        void updatingAScoreReordersTheSet() {
            RedisSortedSet zset = new RedisSortedSet();
            zset.add(member("a"), 1);
            zset.add(member("b"), 2);
            zset.add(member("c"), 3);

            zset.add(member("a"), 99);

            assertThat(zset.snapshot())
                    .extracting(entry -> entry.member().asString())
                    .containsExactly("b", "c", "a");
            zset.checkInvariants();
        }

        @Test
        void rankIsZeroBasedAndReversible() {
            RedisSortedSet zset = new RedisSortedSet();
            zset.add(member("a"), 1);
            zset.add(member("b"), 2);
            zset.add(member("c"), 3);

            assertThat(zset.rank(member("a"))).isZero();
            assertThat(zset.rank(member("c"))).isEqualTo(2);
            assertThat(zset.reverseRank(member("a"))).isEqualTo(2);
            assertThat(zset.reverseRank(member("c"))).isZero();
            assertThat(zset.rank(member("missing"))).isEqualTo(-1);
        }

        @Test
        void rangeHandlesNegativeAndOutOfBoundIndices() {
            RedisSortedSet zset = new RedisSortedSet();
            for (int i = 0; i < 5; i++) {
                zset.add(member("m" + i), i);
            }

            assertThat(names(zset.range(0, -1, false))).containsExactly("m0", "m1", "m2", "m3", "m4");
            assertThat(names(zset.range(1, 3, false))).containsExactly("m1", "m2", "m3");
            assertThat(names(zset.range(-2, -1, false))).containsExactly("m3", "m4");
            assertThat(names(zset.range(0, 100, false))).hasSize(5);
            assertThat(names(zset.range(10, 20, false))).isEmpty();
            assertThat(names(zset.range(3, 1, false))).as("start after stop").isEmpty();
        }

        @Test
        void reverseRangeMirrorsForwardRange() {
            RedisSortedSet zset = new RedisSortedSet();
            for (int i = 0; i < 5; i++) {
                zset.add(member("m" + i), i);
            }

            assertThat(names(zset.range(0, -1, true)))
                    .containsExactly("m4", "m3", "m2", "m1", "m0");
            assertThat(names(zset.range(0, 1, true))).containsExactly("m4", "m3");
        }

        @Test
        void incrementCreatesOrUpdates() {
            RedisSortedSet zset = new RedisSortedSet();

            assertThat(zset.incrementScore(member("a"), 5)).isEqualTo(5.0);
            assertThat(zset.incrementScore(member("a"), -2)).isEqualTo(3.0);
            zset.checkInvariants();
        }

        @Test
        void removeReportsHowManyWerePresent() {
            RedisSortedSet zset = new RedisSortedSet();
            zset.add(member("a"), 1);
            zset.add(member("b"), 2);

            assertThat(zset.remove(List.of(member("a"), member("missing")))).isEqualTo(1);
            assertThat(zset.size()).isEqualTo(1);
            zset.checkInvariants();
        }

        @Test
        @DisplayName("infinite scores sort to the extremes")
        void supportsInfiniteScores() {
            RedisSortedSet zset = new RedisSortedSet();
            zset.add(member("mid"), 0);
            zset.add(member("last"), Double.POSITIVE_INFINITY);
            zset.add(member("first"), Double.NEGATIVE_INFINITY);

            assertThat(names(zset.snapshot())).containsExactly("first", "mid", "last");
            zset.checkInvariants();
        }

        @Test
        void membersAreBinarySafe() {
            RedisSortedSet zset = new RedisSortedSet();
            Bytes awkward = new Bytes(new byte[]{0, '\r', '\n', (byte) 0xFF});
            zset.add(awkward, 1);

            assertThat(zset.score(awkward)).isEqualTo(1.0);
            assertThat(zset.rank(awkward)).isZero();
        }

        @Test
        @DisplayName("members sort by unsigned byte, so non-ASCII does not jump to the front")
        void ordersMembersByUnsignedByteValue() {
            RedisSortedSet zset = new RedisSortedSet();
            Bytes ascii = new Bytes(new byte[]{'a'});          // 0x61
            Bytes highByte = new Bytes(new byte[]{(byte) 0xE9}); // signed -23, unsigned 233
            zset.add(highByte, 1);
            zset.add(ascii, 1);

            // Signed comparison would put 0xE9 first; unsigned correctly puts 'a' first.
            assertThat(zset.snapshot().get(0).member()).isEqualTo(ascii);
        }
    }

    @Nested
    @DisplayName("agreement with a TreeSet oracle")
    class Oracle {

        @RepeatedTest(value = 10, name = "random workload {currentRepetition}/{totalRepetitions}")
        void matchesTheOracleAfterRandomOperations() {
            Random random = new Random();
            RedisSortedSet zset = new RedisSortedSet();
            TreeSet<ScoredMember> oracle = new TreeSet<>(ORDER);

            for (int step = 0; step < 2000; step++) {
                // A small pool of names and scores, so collisions, updates and equal
                // scores all occur frequently rather than by luck.
                Bytes name = member("m" + random.nextInt(60));
                double score = random.nextInt(15);

                if (random.nextInt(100) < 70) {
                    oracle.removeIf(entry -> entry.member().equals(name));
                    oracle.add(new ScoredMember(name, score));
                    zset.add(name, score);
                } else {
                    oracle.removeIf(entry -> entry.member().equals(name));
                    zset.remove(List.of(name));
                }

                if (step % 200 == 0) {
                    zset.checkInvariants();
                }
            }

            zset.checkInvariants();

            List<ScoredMember> expected = new ArrayList<>(oracle);
            List<ScoredMember> actual = zset.snapshot();

            assertThat(actual).hasSameSizeAs(expected);
            for (int i = 0; i < expected.size(); i++) {
                assertThat(actual.get(i).member()).as("member at rank %d", i)
                        .isEqualTo(expected.get(i).member());
                assertThat(actual.get(i).score()).as("score at rank %d", i)
                        .isEqualTo(expected.get(i).score());
            }

            // The part a span bug would break while leaving everything above intact.
            for (int i = 0; i < expected.size(); i++) {
                assertThat(zset.rank(expected.get(i).member()))
                        .as("rank of %s", expected.get(i).member())
                        .isEqualTo(i);
            }
        }

        @RepeatedTest(value = 5, name = "range agreement {currentRepetition}/{totalRepetitions}")
        void rangeQueriesMatchTheOracle() {
            Random random = new Random();
            RedisSortedSet zset = new RedisSortedSet();
            TreeSet<ScoredMember> oracle = new TreeSet<>(ORDER);

            for (int i = 0; i < 300; i++) {
                Bytes name = member("m" + i);
                double score = random.nextInt(50);
                zset.add(name, score);
                oracle.add(new ScoredMember(name, score));
            }

            List<ScoredMember> ordered = new ArrayList<>(oracle);
            for (int trial = 0; trial < 100; trial++) {
                int start = random.nextInt(ordered.size());
                int stop = start + random.nextInt(ordered.size() - start);

                assertThat(names(zset.range(start, stop, false)))
                        .as("range %d..%d", start, stop)
                        .isEqualTo(names(ordered.subList(start, stop + 1)));
            }
        }

        @Test
        @DisplayName("invariants hold through a long insert-then-delete cycle")
        void survivesFullInsertAndDeleteCycle() {
            RedisSortedSet zset = new RedisSortedSet();
            int count = 1000;

            for (int i = 0; i < count; i++) {
                zset.add(member("m" + i), i);
            }
            zset.checkInvariants();
            assertThat(zset.size()).isEqualTo(count);

            // Delete in an order unrelated to insertion, to exercise level demotion.
            for (int i = 0; i < count; i += 2) {
                zset.remove(List.of(member("m" + i)));
            }
            zset.checkInvariants();
            assertThat(zset.size()).isEqualTo(count / 2);

            for (int i = 1; i < count; i += 2) {
                zset.remove(List.of(member("m" + i)));
            }
            zset.checkInvariants();
            assertThat(zset.isEmpty()).isTrue();
        }
    }

    private static List<String> names(List<ScoredMember> entries) {
        List<String> result = new ArrayList<>(entries.size());
        for (ScoredMember entry : entries) {
            result.add(entry.member().asString());
        }
        return result;
    }
}
