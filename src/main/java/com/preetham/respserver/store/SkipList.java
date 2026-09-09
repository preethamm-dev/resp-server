package com.preetham.respserver.store;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A probabilistic ordered structure keeping (score, member) pairs sorted by score, with
 * ties broken lexicographically by member.
 *
 * <h2>Why a skip list and not a balanced tree</h2>
 *
 * A red-black tree gives the same O(log n) bounds, and Java ships one in
 * {@link java.util.TreeMap}. Redis chose a skip list anyway, for reasons that apply here
 * too:
 *
 * <ul>
 *   <li><b>Rank queries.</b> {@code ZRANK} needs "how many elements precede this one".
 *       A tree answers that in O(n) unless every node also stores a subtree size, which
 *       must then be repaired up the whole path on each rotation. A skip list stores a
 *       <em>span</em> on each forward pointer -- how many nodes it jumps -- and rank
 *       falls out of the same descent that finds the element, for free.</li>
 *   <li><b>Range scans.</b> The bottom level is an ordered linked list, so
 *       {@code ZRANGE} is "seek once, then walk". A tree needs repeated successor
 *       lookups.</li>
 *   <li><b>Simplicity.</b> No rotations and no rebalancing. Insert and delete only ever
 *       relink forward pointers, which is far easier to get right -- and to read.</li>
 * </ul>
 *
 * <h2>How the levels work</h2>
 *
 * Each node gets a random height: level 1 with probability 3/4, level 2 with probability
 * 3/16, and so on. Higher levels are express lanes that skip exponentially more nodes,
 * so a search descends like a binary search without any rebalancing:
 *
 * <pre>
 *   L3  H ---------------------------------> 9 -----> null
 *   L2  H --------> 3 -------------------->  9 -----> null
 *   L1  H --> 1 --> 3 --> 5 --------------->  9 -----> null
 *   L0  H --> 1 --> 3 --> 5 --> 6 --> 7 --->  9 -----> null
 *        spans: how many L0 nodes each pointer jumps
 * </pre>
 *
 * The bounds are expected, not worst case: an unlucky run of coin flips degrades to a
 * linked list. With p=0.25 and 32 levels that is vanishingly unlikely, and Redis makes
 * the same bet.
 *
 * <p>Not thread safe. {@link RedisSortedSet} owns the synchronisation.
 */
final class SkipList {

    /** Enough for 4^32 elements at p=0.25 -- far beyond anything this server will hold. */
    private static final int MAX_LEVEL = 32;

    /** Probability a node is promoted to the next level. Redis uses the same value. */
    private static final double P = 0.25;

    private static final class Node {
        final Bytes member;
        double score;
        final Node[] forward;
        /** {@code span[i]} = number of level-0 nodes that {@code forward[i]} jumps over. */
        final int[] span;
        Node backward;

        Node(int level, Bytes member, double score) {
            this.member = member;
            this.score = score;
            this.forward = new Node[level];
            this.span = new int[level];
        }
    }

    private final Node header = new Node(MAX_LEVEL, null, 0);
    private final Random random;
    private Node tail;
    private int level = 1;
    private int length;

    SkipList() {
        this(new Random());
    }

    /** Seeded constructor so tests can reproduce a specific shape. */
    SkipList(Random random) {
        this.random = random;
    }

    int size() {
        return length;
    }

    /**
     * Coin-flip height. The loop is the whole of the "balancing": no rotations, just a
     * geometric distribution that keeps roughly a quarter of the nodes at each level up.
     */
    private int randomLevel() {
        int newLevel = 1;
        while (newLevel < MAX_LEVEL && random.nextDouble() < P) {
            newLevel++;
        }
        return newLevel;
    }

    /** Whether {@code node} sorts strictly before the given (score, member). */
    private static boolean isBefore(Node node, double score, Bytes member) {
        return node.score < score
                || (node.score == score && node.member.compareTo(member) < 0);
    }

    /** Whether {@code node} sorts before or equal to the given (score, member). */
    private static boolean isBeforeOrEqual(Node node, double score, Bytes member) {
        return node.score < score
                || (node.score == score && node.member.compareTo(member) <= 0);
    }

    /**
     * Inserts a pair that is not already present.
     *
     * <p>The tricky part is span maintenance. {@code rank[i]} records how far the search
     * had travelled when it dropped from level i, and the difference between those ranks
     * is exactly how the new node's spans and its predecessors' spans must be split.
     * Getting this wrong does not corrupt ordering -- iteration still works -- it only
     * corrupts {@code ZRANK}, which is why the oracle test compares ranks and not just
     * order.
     */
    void insert(Bytes member, double score) {
        Node[] update = new Node[MAX_LEVEL];
        int[] rank = new int[MAX_LEVEL];

        Node x = header;
        for (int i = level - 1; i >= 0; i--) {
            rank[i] = (i == level - 1) ? 0 : rank[i + 1];
            while (x.forward[i] != null && isBefore(x.forward[i], score, member)) {
                rank[i] += x.span[i];
                x = x.forward[i];
            }
            update[i] = x;
        }

        int newLevel = randomLevel();
        if (newLevel > level) {
            for (int i = level; i < newLevel; i++) {
                rank[i] = 0;
                update[i] = header;
                update[i].span[i] = length;
            }
            level = newLevel;
        }

        Node inserted = new Node(newLevel, member, score);
        for (int i = 0; i < newLevel; i++) {
            inserted.forward[i] = update[i].forward[i];
            update[i].forward[i] = inserted;

            // Split the predecessor's span at the point the new node lands.
            inserted.span[i] = update[i].span[i] - (rank[0] - rank[i]);
            update[i].span[i] = (rank[0] - rank[i]) + 1;
        }
        // Levels above the new node simply jump over one more element.
        for (int i = newLevel; i < level; i++) {
            update[i].span[i]++;
        }

        inserted.backward = (update[0] == header) ? null : update[0];
        if (inserted.forward[0] != null) {
            inserted.forward[0].backward = inserted;
        } else {
            tail = inserted;
        }
        length++;
    }

    /** Removes a pair. Returns false if it was not present. */
    boolean delete(Bytes member, double score) {
        Node[] update = new Node[MAX_LEVEL];

        Node x = header;
        for (int i = level - 1; i >= 0; i--) {
            while (x.forward[i] != null && isBefore(x.forward[i], score, member)) {
                x = x.forward[i];
            }
            update[i] = x;
        }

        Node target = x.forward[0];
        if (target == null || target.score != score || !target.member.equals(member)) {
            return false;
        }

        for (int i = 0; i < level; i++) {
            if (update[i].forward[i] == target) {
                // Absorb the removed node's span into its predecessor, minus the node.
                update[i].span[i] += target.span[i] - 1;
                update[i].forward[i] = target.forward[i];
            } else {
                update[i].span[i]--;
            }
        }

        if (target.forward[0] != null) {
            target.forward[0].backward = target.backward;
        } else {
            tail = target.backward;
        }
        while (level > 1 && header.forward[level - 1] == null) {
            level--;
        }
        length--;
        return true;
    }

    /**
     * Zero-based rank of a pair, or -1 if absent.
     *
     * <p>This is the operation the spans exist for. Accumulating them during the ordinary
     * descent yields the rank in O(log n) with no extra traversal.
     */
    int rank(Bytes member, double score) {
        int traversed = 0;
        Node x = header;

        for (int i = level - 1; i >= 0; i--) {
            while (x.forward[i] != null && isBeforeOrEqual(x.forward[i], score, member)) {
                traversed += x.span[i];
                x = x.forward[i];
            }
            if (x != header && x.member.equals(member)) {
                return traversed - 1;
            }
        }
        return -1;
    }

    /** The node at a zero-based rank, or null. Also a span-driven O(log n) descent. */
    private Node nodeAtRank(int zeroBasedRank) {
        int target = zeroBasedRank + 1; // spans count from the header, so work 1-based
        int traversed = 0;
        Node x = header;

        for (int i = level - 1; i >= 0; i--) {
            while (x.forward[i] != null && traversed + x.span[i] <= target) {
                traversed += x.span[i];
                x = x.forward[i];
            }
            if (traversed == target) {
                return x;
            }
        }
        return null;
    }

    /**
     * Entries between two zero-based ranks, inclusive. Seeks once, then walks the bottom
     * level -- which is an ordinary linked list.
     */
    List<ScoredMember> range(int start, int stop) {
        List<ScoredMember> result = new ArrayList<>();
        if (start > stop || start >= length) {
            return result;
        }
        Node node = nodeAtRank(start);
        for (int i = start; node != null && i <= stop; i++) {
            result.add(new ScoredMember(node.member, node.score));
            node = node.forward[0];
        }
        return result;
    }

    /** Every entry in rank order. Used by tests and by the AOF rewriter. */
    List<ScoredMember> all() {
        List<ScoredMember> result = new ArrayList<>(length);
        for (Node node = header.forward[0]; node != null; node = node.forward[0]) {
            result.add(new ScoredMember(node.member, node.score));
        }
        return result;
    }

    /**
     * Checks the structure's internal invariants. Only used by tests, but kept here
     * because it needs access to the private node layout.
     *
     * @throws IllegalStateException describing the first violation found
     */
    void checkInvariants() {
        if (length == 0) {
            for (int i = 0; i < MAX_LEVEL; i++) {
                if (header.forward[i] != null) {
                    throw new IllegalStateException("empty list has a forward pointer at level " + i);
                }
            }
            return;
        }

        // Ordering on the bottom level.
        Node previous = null;
        int counted = 0;
        for (Node node = header.forward[0]; node != null; node = node.forward[0]) {
            if (previous != null && isBefore(node, previous.score, previous.member)) {
                throw new IllegalStateException(
                        "out of order: " + previous.member + " then " + node.member);
            }
            previous = node;
            counted++;
        }
        if (counted != length) {
            throw new IllegalStateException("length is " + length + " but " + counted + " nodes");
        }
        if (tail != previous) {
            throw new IllegalStateException("tail pointer does not point at the last node");
        }

        // Every span must equal the real distance along level 0.
        for (int i = 0; i < level; i++) {
            int position = 0;
            for (Node node = header; node != null; node = node.forward[i]) {
                if (node.forward[i] == null) {
                    break;
                }
                int expected = distanceOnBottomLevel(node, node.forward[i]);
                if (node.span[i] != expected) {
                    throw new IllegalStateException("span[" + i + "] at position " + position
                            + " is " + node.span[i] + " but the real distance is " + expected);
                }
                position++;
            }
        }
    }

    private int distanceOnBottomLevel(Node from, Node to) {
        int distance = 0;
        Node node = (from == header) ? header.forward[0] : from.forward[0];
        distance = 1;
        while (node != to && node != null) {
            node = node.forward[0];
            distance++;
        }
        return distance;
    }
}
