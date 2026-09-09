package com.preetham.respserver.store;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A sorted set: unique members, each with a score, ordered by score and then by member.
 *
 * <h2>Two structures, one collection</h2>
 *
 * This holds both a {@link HashMap} and a {@link SkipList} over the same data, which is
 * exactly how Redis implements it. Neither alone is enough:
 *
 * <ul>
 *   <li>{@code ZSCORE member} must be O(1). A skip list would make it O(log n) at best,
 *       and it is one of the most frequently called commands.</li>
 *   <li>{@code ZRANGE}, {@code ZRANK} and range scans need ordering, which a hash map
 *       cannot provide at all.</li>
 * </ul>
 *
 * The cost is that every mutation touches both structures and they must not diverge --
 * a member present in one but not the other is a corrupt collection. Keeping the two in
 * step is the whole of the complexity here, and it is why every mutation below is written
 * as "remove the old pair from both, then insert the new pair into both" rather than
 * trying to update in place.
 *
 * <p>Synchronised per instance, for the reason described on {@link RedisList}.
 */
public final class RedisSortedSet implements RedisObject {

    private final Map<Bytes, Double> scores = new HashMap<>();
    private final SkipList index = new SkipList();

    @Override
    public String typeName() {
        return "zset";
    }

    public synchronized int size() {
        return scores.size();
    }

    public synchronized boolean isEmpty() {
        return scores.isEmpty();
    }

    /**
     * Adds or updates one member.
     *
     * <p>A score change is a delete plus an insert, because the member's position in the
     * ordering depends on its score -- mutating the score in place would leave the skip
     * list out of order and quietly corrupt every subsequent range query.
     *
     * @return true if the member is new, which is what {@code ZADD} counts
     */
    public synchronized boolean add(Bytes member, double score) {
        Double existing = scores.get(member);
        if (existing == null) {
            scores.put(member, score);
            index.insert(member, score);
            return true;
        }
        if (existing != score) {
            index.delete(member, existing);
            scores.put(member, score);
            index.insert(member, score);
        }
        return false;
    }

    /**
     * Adds {@code delta} to a member's score, creating it at {@code delta} if absent.
     *
     * @return the new score
     */
    public synchronized double incrementScore(Bytes member, double delta) {
        Double existing = scores.get(member);
        double updated = existing == null ? delta : existing + delta;
        if (existing != null) {
            index.delete(member, existing);
        }
        scores.put(member, updated);
        index.insert(member, updated);
        return updated;
    }

    /** The member's score, or null when absent. */
    public synchronized Double score(Bytes member) {
        return scores.get(member);
    }

    /** Zero-based rank in ascending score order, or -1 when absent. */
    public synchronized int rank(Bytes member) {
        Double score = scores.get(member);
        return score == null ? -1 : index.rank(member, score);
    }

    /** Zero-based rank in descending order, or -1 when absent. */
    public synchronized int reverseRank(Bytes member) {
        int ascending = rank(member);
        return ascending < 0 ? -1 : scores.size() - 1 - ascending;
    }

    /** Removes members, returning how many were present. */
    public synchronized int remove(List<Bytes> toRemove) {
        int removed = 0;
        for (Bytes member : toRemove) {
            Double score = scores.remove(member);
            if (score != null) {
                index.delete(member, score);
                removed++;
            }
        }
        return removed;
    }

    /**
     * Entries between two ranks, inclusive, honouring Redis's negative indices where -1
     * is the highest-scoring member. Out-of-range indices are clamped rather than
     * rejected, so {@code ZRANGE k 0 -1} always returns the whole set.
     */
    public synchronized List<ScoredMember> range(long start, long stop, boolean reverse) {
        int size = scores.size();
        if (size == 0) {
            return List.of();
        }

        long from = start < 0 ? start + size : start;
        long to = stop < 0 ? stop + size : stop;
        if (from < 0) {
            from = 0;
        }
        if (to >= size) {
            to = size - 1;
        }
        if (from > to || from >= size) {
            return List.of();
        }

        if (!reverse) {
            return index.range((int) from, (int) to);
        }

        // Descending: translate the requested ranks onto the ascending index, fetch, and
        // flip. Cheaper and far less error-prone than maintaining backward pointers for
        // ranked access as well.
        int ascendingFrom = size - 1 - (int) to;
        int ascendingTo = size - 1 - (int) from;
        List<ScoredMember> ascending = index.range(ascendingFrom, ascendingTo);
        List<ScoredMember> descending = new ArrayList<>(ascending.size());
        for (int i = ascending.size() - 1; i >= 0; i--) {
            descending.add(ascending.get(i));
        }
        return descending;
    }

    /** Every entry in ascending order. Used by tests and the AOF rewriter. */
    public synchronized List<ScoredMember> snapshot() {
        return index.all();
    }

    /**
     * Verifies the hash map and the skip list still agree. Test-only, but it lives here
     * because it needs both private structures.
     *
     * @throws IllegalStateException on the first divergence found
     */
    synchronized void checkInvariants() {
        index.checkInvariants();

        if (index.size() != scores.size()) {
            throw new IllegalStateException(
                    "skip list holds " + index.size() + " but the map holds " + scores.size());
        }
        for (ScoredMember entry : index.all()) {
            Double mapped = scores.get(entry.member());
            if (mapped == null) {
                throw new IllegalStateException(
                        "member " + entry.member() + " is in the skip list but not the map");
            }
            if (mapped != entry.score()) {
                throw new IllegalStateException("member " + entry.member() + " has score "
                        + entry.score() + " in the skip list but " + mapped + " in the map");
            }
        }
    }
}
