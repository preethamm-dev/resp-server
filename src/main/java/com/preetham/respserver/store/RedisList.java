package com.preetham.respserver.store;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

/**
 * A list value, pushed and popped from either end.
 *
 * <p>Backed by {@link ArrayDeque}, which gives O(1) at both ends -- the operations lists
 * are actually used for, as a queue or a stack. An {@link java.util.ArrayList} would make
 * {@code LPUSH} O(n) because every element shifts; a {@link java.util.LinkedList} would
 * pay an object header and two pointers per element. Redis uses a quicklist (a linked
 * list of compact array nodes) to get both properties at once; that is a memory
 * optimisation this server does not need.
 *
 * <p>{@code LRANGE} is O(offset + count) since a deque has no random access. That matches
 * Redis's own documented complexity of O(S+N), so nothing is lost.
 *
 * <h2>Thread safety</h2>
 *
 * Every method is {@code synchronized} on the instance. Mutations additionally run inside
 * {@code ConcurrentHashMap.compute} in {@link Database}, which serialises them per key;
 * the lock here is what stops a concurrent <em>reader</em> from seeing a half-updated
 * deque. Both are needed: the map protects the mapping, this protects the value.
 */
public final class RedisList implements RedisObject {

    private final Deque<Bytes> elements = new ArrayDeque<>();

    @Override
    public String typeName() {
        return "list";
    }

    public synchronized int size() {
        return elements.size();
    }

    public synchronized boolean isEmpty() {
        return elements.isEmpty();
    }

    /** Pushes to the head. Redis pushes each argument in turn, so {@code LPUSH k a b} leaves b first. */
    public synchronized int leftPush(List<Bytes> values) {
        for (Bytes value : values) {
            elements.addFirst(value);
        }
        return elements.size();
    }

    public synchronized int rightPush(List<Bytes> values) {
        for (Bytes value : values) {
            elements.addLast(value);
        }
        return elements.size();
    }

    /** Pops from the head, or null when empty. */
    public synchronized Bytes leftPop() {
        return elements.pollFirst();
    }

    public synchronized Bytes rightPop() {
        return elements.pollLast();
    }

    /** Pops up to {@code count} elements from the head, in order. */
    public synchronized List<Bytes> leftPop(int count) {
        List<Bytes> popped = new ArrayList<>(Math.min(count, elements.size()));
        for (int i = 0; i < count && !elements.isEmpty(); i++) {
            popped.add(elements.pollFirst());
        }
        return popped;
    }

    public synchronized List<Bytes> rightPop(int count) {
        List<Bytes> popped = new ArrayList<>(Math.min(count, elements.size()));
        for (int i = 0; i < count && !elements.isEmpty(); i++) {
            popped.add(elements.pollLast());
        }
        return popped;
    }

    /**
     * Elements between two indices, inclusive, accepting Redis's negative indices where
     * -1 is the last element.
     *
     * <p>Out-of-range indices are clamped rather than rejected: {@code LRANGE k 0 -1} on a
     * missing or short list returns what exists instead of an error. That is deliberate
     * in Redis and clients rely on it.
     */
    public synchronized List<Bytes> range(long start, long stop) {
        int size = elements.size();
        if (size == 0) {
            return List.of();
        }

        long from = normaliseIndex(start, size);
        long to = normaliseIndex(stop, size);

        if (from < 0) {
            from = 0;
        }
        if (to >= size) {
            to = size - 1;
        }
        if (from > to || from >= size) {
            return List.of();
        }

        List<Bytes> result = new ArrayList<>((int) (to - from + 1));
        Iterator<Bytes> iterator = elements.iterator();
        for (int i = 0; iterator.hasNext() && i <= to; i++) {
            Bytes element = iterator.next();
            if (i >= from) {
                result.add(element);
            }
        }
        return result;
    }

    /** Element at an index, honouring negative indices. Null when out of range. */
    public synchronized Bytes get(long index) {
        int size = elements.size();
        long resolved = normaliseIndex(index, size);
        if (resolved < 0 || resolved >= size) {
            return null;
        }
        Iterator<Bytes> iterator = elements.iterator();
        for (int i = 0; i < resolved; i++) {
            iterator.next();
        }
        return iterator.next();
    }

    /** Snapshot in head-to-tail order. Used by the AOF rewriter. */
    public synchronized List<Bytes> snapshot() {
        return new ArrayList<>(elements);
    }

    /** Maps a possibly negative index onto a forward one; -1 means the last element. */
    private static long normaliseIndex(long index, int size) {
        return index < 0 ? index + size : index;
    }
}
