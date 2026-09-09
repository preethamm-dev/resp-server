package com.preetham.respserver.store;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The keyspace: a concurrent map from key to {@link ValueHolder}.
 *
 * <h2>Concurrency model</h2>
 *
 * The virtual-thread server runs many threads against this map at once, so every
 * read-modify-write must be atomic. Rather than taking a global lock -- which would
 * serialise the whole server and throw away the point of having many threads -- this
 * class uses {@link ConcurrentHashMap#compute}, which locks only the bin holding that
 * one key. Two clients touching different keys never contend; two clients touching
 * the same key are serialised, which is exactly the required semantics.
 *
 * <p>The event-loop server has a single thread and needs none of this. That contrast
 * is the central trade the project measures: virtual threads buy a simpler blocking
 * programming model and real parallelism, and pay for it in synchronisation that a
 * single-threaded design gets for free. It is also why real Redis is single-threaded.
 *
 * <h2>Expiry</h2>
 *
 * Expiry here is <em>lazy</em>: a key past its deadline is treated as absent and
 * deleted when something touches it. That alone leaks memory for keys nobody reads
 * again, which is why Redis also runs an active sampling reaper -- added on day 2.
 *
 * <p>Note the use of the two-argument {@link ConcurrentHashMap#remove(Object, Object)}
 * when reaping. Checking "expired?" and then removing by key alone would be a race: a
 * concurrent {@code SET} could replace the value between the two steps and we would
 * delete the fresh value. Removing only if the holder is still the one we inspected
 * closes that window.
 */
public final class Database {

    /** {@code TTL} reply when the key does not exist. */
    public static final long TTL_KEY_NOT_FOUND = -2L;

    /** {@code TTL} reply when the key exists but has no expiry. */
    public static final long TTL_NO_EXPIRY = -1L;

    private final ConcurrentHashMap<String, ValueHolder> keyspace = new ConcurrentHashMap<>();

    /**
     * Injectable clock. Tests drive expiry by moving this forward instead of sleeping,
     * which keeps the suite fast and deterministic.
     */
    private final LongSupplier clock;

    public Database() {
        this(System::currentTimeMillis);
    }

    /**
     * Counts mutations that actually changed the dataset.
     *
     * <h2>Why this exists</h2>
     *
     * The append-only file must record a command only if it really modified something.
     * Appending unconditionally looks harmless but is not: {@code SET k v NX} against an
     * existing key changes nothing, yet replaying it into an empty keyspace at startup
     * <em>would</em> create the key. The dataset after recovery would differ from the one
     * that was saved.
     *
     * <p>So the command layer samples this counter before and after a handler runs and
     * persists only when it moved. Redis solves the same problem the same way, with its
     * {@code server.dirty} counter.
     */
    private final LongAdder dirty = new LongAdder();

    public Database(LongSupplier clock) {
        this.clock = clock;
    }

    public long now() {
        return clock.getAsLong();
    }

    /** Number of dataset-changing operations so far. See {@link #dirty}. */
    public long dirtyCount() {
        return dirty.sum();
    }

    private void markDirty() {
        dirty.increment();
    }

    // ---- reads -----------------------------------------------------------------

    /** The live holder for a key, reaping it first if it has expired. */
    private ValueHolder liveHolder(String key) {
        ValueHolder holder = keyspace.get(key);
        if (holder == null) {
            return null;
        }
        if (holder.isExpired(now())) {
            keyspace.remove(key, holder);
            return null;
        }
        return holder;
    }

    public Optional<RedisObject> get(String key) {
        ValueHolder holder = liveHolder(key);
        return holder == null ? Optional.empty() : Optional.of(holder.value());
    }

    /**
     * The value, checked against an expected type.
     *
     * <p>Reads do not take the keyspace lock. That is safe because the value returned is
     * either immutable ({@link RedisString}) or internally synchronised (the collection
     * types), so a reader can never observe a half-applied mutation. It does mean a read
     * racing a {@code DEL} may return data from a key that has just been deleted, which
     * is a benign ordering question rather than a correctness one -- the read is simply
     * ordered before the delete.
     *
     * @throws WrongTypeException if the key holds a different type
     */
    public <T extends RedisObject> Optional<T> getAs(String key, Class<T> type) {
        ValueHolder holder = liveHolder(key);
        if (holder == null) {
            return Optional.empty();
        }
        if (!type.isInstance(holder.value())) {
            throw new WrongTypeException();
        }
        return Optional.of(type.cast(holder.value()));
    }

    /**
     * The value as a string.
     *
     * @throws WrongTypeException if the key holds another type
     */
    public Optional<RedisString> getString(String key) {
        return getAs(key, RedisString.class);
    }

    /**
     * Runs a mutation against a collection value, creating the key if it is absent and
     * deleting it if the mutation leaves the collection empty.
     *
     * <p>The whole sequence -- type check, create-if-absent, mutate, delete-if-empty --
     * happens inside {@code compute}, so it is atomic with respect to every other
     * operation on that key. Doing it as separate get/mutate/put calls would allow a
     * concurrent {@code DEL} to land in the middle and lose the write.
     *
     * <p>An existing TTL is preserved: pushing onto a list that expires in ten seconds
     * must not make it immortal.
     *
     * @param factory  builds an empty collection when the key does not exist
     * @param mutation applied to the collection; its result is returned to the caller
     */
    public <T extends RedisObject, R> R mutateCollection(String key,
                                                         Class<T> type,
                                                         Supplier<T> factory,
                                                         Function<T, R> mutation) {
        List<R> result = new ArrayList<>(1);
        keyspace.compute(key, (k, existing) -> {
            T target;
            long expireAt = ValueHolder.NO_EXPIRY;

            if (existing != null && !existing.isExpired(now())) {
                if (!type.isInstance(existing.value())) {
                    throw new WrongTypeException();
                }
                target = type.cast(existing.value());
                expireAt = existing.expireAtMillis();
            } else {
                target = factory.get();
            }

            result.add(mutation.apply(target));

            // Redis has no concept of an empty collection: the key goes with the last
            // element. Returning null from compute removes the mapping.
            return target.isEmpty() ? null : new ValueHolder(target, expireAt);
        });

        // Marked dirty unconditionally rather than asking the mutation whether it
        // changed anything. Over-recording is harmless here: replaying an SREM of an
        // absent member or an LPOP of a missing key is a no-op, so recovery still
        // reproduces the same dataset. The conditional string writes above cannot take
        // that shortcut, because replaying a failed NX would create a key.
        markDirty();
        return result.get(0);
    }

    public boolean exists(String key) {
        return liveHolder(key) != null;
    }

    public Optional<String> type(String key) {
        ValueHolder holder = liveHolder(key);
        return holder == null ? Optional.empty() : Optional.of(holder.value().typeName());
    }

    /**
     * Number of live keys.
     *
     * <p>This is O(n) because it skips keys that are past their deadline but not yet
     * reaped. Real Redis answers {@code DBSIZE} in O(1) from the dictionary size and
     * accepts that the count can transiently include expired keys. Correctness is
     * worth more than O(1) here, and the keyspaces this server is benchmarked with
     * are small enough that it does not matter.
     */
    public int size() {
        long nowMillis = now();
        int count = 0;
        for (ValueHolder holder : keyspace.values()) {
            if (!holder.isExpired(nowMillis)) {
                count++;
            }
        }
        return count;
    }

    /** Live keys matching a glob pattern. */
    public List<String> keys(String pattern) {
        long nowMillis = now();
        List<String> matches = new ArrayList<>();
        for (Map.Entry<String, ValueHolder> entry : keyspace.entrySet()) {
            if (!entry.getValue().isExpired(nowMillis) && GlobMatcher.matches(pattern, entry.getKey())) {
                matches.add(entry.getKey());
            }
        }
        return matches;
    }

    // ---- writes ----------------------------------------------------------------

    /** Unconditional set, clearing any existing TTL (this is Redis's {@code SET} behaviour). */
    public void set(String key, RedisObject value) {
        keyspace.put(key, ValueHolder.of(value));
        markDirty();
    }

    public void set(String key, RedisObject value, long expireAtMillis) {
        keyspace.put(key, new ValueHolder(value, expireAtMillis));
        markDirty();
    }

    /** {@code SET ... NX} -- store only if the key is absent or expired. */
    public boolean setIfAbsent(String key, RedisObject value, long expireAtMillis) {
        boolean[] stored = new boolean[1];
        keyspace.compute(key, (k, existing) -> {
            if (existing != null && !existing.isExpired(now())) {
                return existing;
            }
            stored[0] = true;
            return new ValueHolder(value, expireAtMillis);
        });
        if (stored[0]) {
            markDirty();
        }
        return stored[0];
    }

    /** {@code SET ... XX} -- store only if the key already exists and is live. */
    public boolean setIfPresent(String key, RedisObject value, long expireAtMillis) {
        boolean[] stored = new boolean[1];
        keyspace.compute(key, (k, existing) -> {
            if (existing == null || existing.isExpired(now())) {
                // Returning null both leaves an absent key absent and reaps an
                // expired one, so the two cases collapse into one.
                return null;
            }
            stored[0] = true;
            return new ValueHolder(value, expireAtMillis);
        });
        if (stored[0]) {
            markDirty();
        }
        return stored[0];
    }

    public boolean delete(String key) {
        ValueHolder removed = keyspace.remove(key);
        if (removed != null) {
            markDirty();
        }
        // A key already past its deadline counts as absent, so deleting it reports 0.
        return removed != null && !removed.isExpired(now());
    }

    public long delete(List<String> keys) {
        long removed = 0;
        for (String key : keys) {
            if (delete(key)) {
                removed++;
            }
        }
        return removed;
    }

    public void flushAll() {
        if (!keyspace.isEmpty()) {
            markDirty();
        }
        keyspace.clear();
    }

    // ---- atomic read-modify-write ----------------------------------------------

    /**
     * {@code INCRBY} / {@code DECRBY}. A missing key is treated as 0, and an existing
     * TTL is preserved -- incrementing a counter must not resurrect it forever.
     *
     * <p>The whole read-parse-add-write sequence runs inside {@code compute}, so it is
     * atomic per key. Doing it as a separate get and set would lose increments under
     * concurrency; the 100-virtual-threads test exists to prove this version does not.
     *
     * @throws NotAnIntegerException if the current value is not a canonical integer
     * @throws WrongTypeException    if the key holds a non-string type
     * @throws RedisDataException    if the result would overflow 64 bits
     */
    public long incrBy(String key, long delta) {
        long[] result = new long[1];
        keyspace.compute(key, (k, existing) -> {
            long base = 0;
            long expireAt = ValueHolder.NO_EXPIRY;

            if (existing != null && !existing.isExpired(now())) {
                if (!(existing.value() instanceof RedisString s)) {
                    throw new WrongTypeException();
                }
                base = s.asLong();
                expireAt = existing.expireAtMillis();
            }

            long next;
            try {
                next = Math.addExact(base, delta);
            } catch (ArithmeticException e) {
                throw new RedisDataException("ERR increment or decrement would overflow");
            }

            result[0] = next;
            return new ValueHolder(RedisString.of(next), expireAt);
        });
        markDirty();
        return result[0];
    }

    /** {@code APPEND}. Returns the new length. A missing key behaves as an empty string. */
    public int append(String key, byte[] suffix) {
        int[] newLength = new int[1];
        keyspace.compute(key, (k, existing) -> {
            RedisString current = null;
            long expireAt = ValueHolder.NO_EXPIRY;

            if (existing != null && !existing.isExpired(now())) {
                if (!(existing.value() instanceof RedisString s)) {
                    throw new WrongTypeException();
                }
                current = s;
                expireAt = existing.expireAtMillis();
            }

            RedisString appended = current == null
                    ? new RedisString(suffix.clone())
                    : current.append(suffix);
            newLength[0] = appended.length();
            return new ValueHolder(appended, expireAt);
        });
        markDirty();
        return newLength[0];
    }

    // ---- expiry ----------------------------------------------------------------

    /** Sets an absolute deadline. Returns false if the key does not exist. */
    public boolean expireAt(String key, long atMillis) {
        ValueHolder updated = keyspace.computeIfPresent(key, (k, existing) ->
                existing.isExpired(now()) ? null : existing.withExpiry(atMillis));
        if (updated != null) {
            markDirty();
        }
        return updated != null;
    }

    /** Clears any deadline. Returns false if the key does not exist or had no TTL. */
    public boolean persist(String key) {
        boolean[] changed = new boolean[1];
        keyspace.computeIfPresent(key, (k, existing) -> {
            if (existing.isExpired(now())) {
                return null;
            }
            if (!existing.hasExpiry()) {
                return existing;
            }
            changed[0] = true;
            return existing.persistent();
        });
        if (changed[0]) {
            markDirty();
        }
        return changed[0];
    }

    /**
     * Remaining lifetime in milliseconds, or {@link #TTL_NO_EXPIRY} /
     * {@link #TTL_KEY_NOT_FOUND}, matching Redis's {@code PTTL} reply.
     */
    public long ttlMillis(String key) {
        ValueHolder holder = liveHolder(key);
        if (holder == null) {
            return TTL_KEY_NOT_FOUND;
        }
        return holder.hasExpiry() ? holder.ttlMillis(now()) : TTL_NO_EXPIRY;
    }

    // ---- support for the active expiry cycle -----------------------------------

    /**
     * Rotating cursor for {@link #sampleKeysForExpiry}. Guarded by {@code this}; the
     * expiry task is the only caller and there is exactly one of it.
     */
    private Iterator<String> expiryCursor;

    /**
     * Returns up to {@code count} keys for the expiry cycle to examine, resuming where
     * the previous call left off and wrapping around at the end.
     *
     * <h2>Why a cursor rather than random sampling</h2>
     *
     * Redis picks random keys from a dedicated dictionary of keys that have a TTL, which
     * it can do in O(1). Neither half of that is available here: {@link ConcurrentHashMap}
     * offers no O(1) random selection, and maintaining a parallel index of volatile keys
     * would mean touching a second structure on every {@code SET}, {@code EXPIRE},
     * {@code PERSIST} and {@code DEL} -- bookkeeping on the hot path to speed up a
     * background task.
     *
     * <p>A rotating cursor gives the same eventual coverage with bounded work per cycle.
     * It wastes some effort on keys that have no TTL, which is the cost of not keeping
     * that second index. The iterator is weakly consistent, so concurrent writes never
     * make it throw; it may simply miss or repeat a key, which for a best-effort reaper
     * is harmless.
     */
    synchronized List<String> sampleKeysForExpiry(int count) {
        List<String> sample = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            if (expiryCursor == null || !expiryCursor.hasNext()) {
                if (keyspace.isEmpty()) {
                    break;
                }
                expiryCursor = keyspace.keySet().iterator();
                if (!expiryCursor.hasNext()) {
                    break;
                }
            }
            sample.add(expiryCursor.next());
        }
        return sample;
    }

    /**
     * Removes a key if it is past its deadline.
     *
     * @return true if the key was expired and has now been removed
     */
    boolean reapIfExpired(String key) {
        ValueHolder holder = keyspace.get(key);
        if (holder == null || !holder.isExpired(now())) {
            return false;
        }
        // Remove only if it is still the same holder -- a concurrent SET may have
        // replaced it with a fresh value between the check and the removal.
        return keyspace.remove(key, holder);
    }

    /** Number of entries including any that are expired but not yet reaped. */
    int rawSize() {
        return keyspace.size();
    }
}
