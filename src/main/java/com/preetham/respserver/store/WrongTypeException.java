package com.preetham.respserver.store;

/**
 * Thrown when a command is applied to a key holding a different type -- for example
 * {@code LPUSH} against a string, or {@code INCR} against a list.
 *
 * <p>Redis never coerces between types; it fails the command. Note the error code is
 * {@code WRONGTYPE}, not the usual {@code ERR}: clients such as Jedis match on that
 * exact prefix to raise a distinct exception, so the wording matters for real
 * protocol compatibility.
 */
public class WrongTypeException extends RedisDataException {

    private static final long serialVersionUID = 1L;

    public static final String MESSAGE =
            "WRONGTYPE Operation against a key holding the wrong kind of value";

    public WrongTypeException() {
        super(MESSAGE);
    }
}
