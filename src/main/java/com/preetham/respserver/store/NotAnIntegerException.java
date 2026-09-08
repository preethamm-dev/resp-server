package com.preetham.respserver.store;

/**
 * Thrown when a value is used as a number but is not a canonical 64-bit integer.
 *
 * <p>Redis is strict about what counts as canonical: {@code " 1"}, {@code "1.0"},
 * {@code "007"} and {@code "+1"} are all rejected. If they were accepted, {@code INCR}
 * would silently rewrite the stored text and the value would not round-trip.
 */
public class NotAnIntegerException extends RedisDataException {

    private static final long serialVersionUID = 1L;

    public static final String MESSAGE = "ERR value is not an integer or out of range";

    public NotAnIntegerException() {
        super(MESSAGE);
    }
}
