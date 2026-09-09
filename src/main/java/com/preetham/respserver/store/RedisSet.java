package com.preetham.respserver.store;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A set of unique, binary-safe members.
 *
 * <p>{@link LinkedHashSet} for stable iteration, as with {@link RedisHash}. Redis
 * guarantees no order for {@code SMEMBERS}, so this is a convenience for tests and
 * readable output rather than a contract -- and the tests assert set equality rather
 * than sequence, so they would still pass if the backing collection changed.
 *
 * <p>Membership relies entirely on {@link Bytes} having value semantics. Backing this
 * with raw {@code byte[]} would use identity equality and every {@code SADD} would appear
 * to succeed, silently filling the set with duplicates.
 */
public final class RedisSet implements RedisObject {

    private final Set<Bytes> members = new LinkedHashSet<>();

    @Override
    public String typeName() {
        return "set";
    }

    public synchronized int size() {
        return members.size();
    }

    public synchronized boolean isEmpty() {
        return members.isEmpty();
    }

    /** Adds members, returning how many were new -- what {@code SADD} replies. */
    public synchronized int add(List<Bytes> toAdd) {
        int added = 0;
        for (Bytes member : toAdd) {
            if (members.add(member)) {
                added++;
            }
        }
        return added;
    }

    /** Removes members, returning how many were present. */
    public synchronized int remove(List<Bytes> toRemove) {
        int removed = 0;
        for (Bytes member : toRemove) {
            if (members.remove(member)) {
                removed++;
            }
        }
        return removed;
    }

    public synchronized boolean contains(Bytes member) {
        return members.contains(member);
    }

    public synchronized List<Bytes> snapshot() {
        return new ArrayList<>(members);
    }
}
