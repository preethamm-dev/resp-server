package com.preetham.respserver.store;

/**
 * A store-level failure that maps directly onto a RESP error reply.
 *
 * <p>The exception carries the <em>complete</em> reply text including its error
 * code, because Redis does not use a single code for everything: a type mismatch
 * replies {@code WRONGTYPE ...} while most other failures reply {@code ERR ...}.
 * Real clients branch on that prefix -- Jedis, for instance, raises a distinct
 * exception type for {@code WRONGTYPE} -- so getting it wrong breaks compatibility
 * in a way that is easy to miss.
 *
 * <p>Unchecked, because these are thrown from inside
 * {@code ConcurrentHashMap.compute} lambdas, which cannot declare checked
 * exceptions. The command layer catches the base type and replies with
 * {@link #respError()}.
 */
public class RedisDataException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String respError;

    public RedisDataException(String respError) {
        super(respError);
        this.respError = respError;
    }

    /** The full error line to send back, e.g. {@code "ERR value is not an integer or out of range"}. */
    public String respError() {
        return respError;
    }
}
