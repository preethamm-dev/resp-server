package com.preetham.respserver.store;

/**
 * A stored value together with its expiry deadline.
 *
 * <p>Expiry is stored as an absolute wall-clock instant rather than a remaining
 * duration, so no bookkeeping is needed as time passes -- a key is expired precisely
 * when {@code now >= expireAtMillis}. {@link #NO_EXPIRY} means the key lives until
 * it is deleted.
 *
 * <p>Holders are immutable. Changing a value or its TTL replaces the whole holder,
 * which is what allows the keyspace to be updated atomically with a single
 * compare-and-set on the concurrent map rather than a lock.
 */
public record ValueHolder(RedisObject value, long expireAtMillis) {

    public static final long NO_EXPIRY = -1L;

    public ValueHolder {
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
    }

    /** A holder with no TTL. */
    public static ValueHolder of(RedisObject value) {
        return new ValueHolder(value, NO_EXPIRY);
    }

    public boolean hasExpiry() {
        return expireAtMillis != NO_EXPIRY;
    }

    public boolean isExpired(long nowMillis) {
        return hasExpiry() && nowMillis >= expireAtMillis;
    }

    /** Remaining lifetime in milliseconds, or {@link #NO_EXPIRY} if the key is persistent. */
    public long ttlMillis(long nowMillis) {
        return hasExpiry() ? Math.max(0, expireAtMillis - nowMillis) : NO_EXPIRY;
    }

    public ValueHolder withValue(RedisObject newValue) {
        return new ValueHolder(newValue, expireAtMillis);
    }

    public ValueHolder withExpiry(long newExpireAtMillis) {
        return new ValueHolder(value, newExpireAtMillis);
    }

    public ValueHolder persistent() {
        return new ValueHolder(value, NO_EXPIRY);
    }
}
