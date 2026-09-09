package com.preetham.respserver.store;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A hash value: a map from field to value, both binary safe.
 *
 * <p>{@link LinkedHashMap} rather than {@link java.util.HashMap} so that
 * {@code HGETALL} and {@code HKEYS} return fields in a stable order. Redis makes no
 * ordering guarantee here, so anything would be conforming -- but a stable order makes
 * the tests deterministic and the output readable, at the cost of one extra pair of
 * pointers per entry.
 *
 * <p>Synchronised per instance, for the reason described on {@link RedisList}.
 */
public final class RedisHash implements RedisObject {

    private final Map<Bytes, Bytes> fields = new LinkedHashMap<>();

    @Override
    public String typeName() {
        return "hash";
    }

    public synchronized int size() {
        return fields.size();
    }

    public synchronized boolean isEmpty() {
        return fields.isEmpty();
    }

    /**
     * Sets field/value pairs.
     *
     * @return how many fields were <em>new</em>, which is what {@code HSET} replies --
     *         note it counts additions, not writes, so overwriting an existing field
     *         contributes zero
     */
    public synchronized int put(List<Bytes> fieldValuePairs) {
        int added = 0;
        for (int i = 0; i < fieldValuePairs.size(); i += 2) {
            Bytes field = fieldValuePairs.get(i);
            Bytes value = fieldValuePairs.get(i + 1);
            if (fields.put(field, value) == null) {
                added++;
            }
        }
        return added;
    }

    public synchronized Bytes get(Bytes field) {
        return fields.get(field);
    }

    public synchronized boolean contains(Bytes field) {
        return fields.containsKey(field);
    }

    /** Removes fields, returning how many were actually present. */
    public synchronized int remove(List<Bytes> fieldsToRemove) {
        int removed = 0;
        for (Bytes field : fieldsToRemove) {
            if (fields.remove(field) != null) {
                removed++;
            }
        }
        return removed;
    }

    /** Field, value, field, value ... flattened, which is the shape {@code HGETALL} replies with. */
    public synchronized List<Bytes> flattened() {
        List<Bytes> flat = new ArrayList<>(fields.size() * 2);
        for (Map.Entry<Bytes, Bytes> entry : fields.entrySet()) {
            flat.add(entry.getKey());
            flat.add(entry.getValue());
        }
        return flat;
    }

    public synchronized List<Bytes> fieldNames() {
        return new ArrayList<>(fields.keySet());
    }

    public synchronized List<Bytes> values() {
        return new ArrayList<>(fields.values());
    }
}
