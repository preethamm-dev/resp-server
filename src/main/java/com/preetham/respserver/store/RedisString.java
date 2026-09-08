package com.preetham.respserver.store;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A binary-safe string value.
 *
 * <p>The payload is {@code byte[]}, not {@code String}: Redis string values may hold
 * arbitrary bytes -- a JPEG, a protobuf, a NUL in the middle -- and decoding through
 * a charset would corrupt them.
 *
 * <p>Instances are immutable, so no locking is needed. Commands that "modify" a
 * string ({@code APPEND}, {@code INCR}) build a new instance and swap it into the
 * keyspace atomically; see {@link Database}.
 *
 * <p>Numeric commands do not cache a parsed value. Redis stores an integer encoding
 * internally as an optimisation, but that is a memory/CPU trade this project does not
 * need, and parsing on demand keeps the type honest: the value on the wire is the
 * value in the store.
 */
public record RedisString(byte[] value) implements RedisObject {

    public RedisString {
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
    }

    public static RedisString of(String s) {
        return new RedisString(s.getBytes(StandardCharsets.UTF_8));
    }

    public static RedisString of(long n) {
        return new RedisString(Long.toString(n).getBytes(StandardCharsets.US_ASCII));
    }

    @Override
    public String typeName() {
        return "string";
    }

    public int length() {
        return value.length;
    }

    public String asString() {
        return new String(value, StandardCharsets.UTF_8);
    }

    /**
     * Interprets the value as a 64-bit integer.
     *
     * <p>Redis is strict here: {@code " 1"}, {@code "1.0"} and {@code "01"} are all
     * rejected, because accepting them would make {@code INCR} lossy -- the stored
     * text would not round-trip. {@link Long#parseLong} rejects the first two;
     * leading zeroes are rejected explicitly.
     *
     * @throws NotAnIntegerException if the value is not a canonical integer
     */
    public long asLong() {
        String s = new String(value, StandardCharsets.US_ASCII);
        if (s.isEmpty()) {
            throw new NotAnIntegerException();
        }

        // Reject every form that would not round-trip. Note that Long.parseLong is
        // more permissive than Redis: it happily accepts a leading '+', so "+1" would
        // parse as 1 and INCR would rewrite the stored text to "2" -- a silent
        // mutation of the client's data. The explicit checks below close that gap.
        if (s.charAt(0) == '+') {
            throw new NotAnIntegerException();
        }
        String digits = s.startsWith("-") ? s.substring(1) : s;
        boolean hasLeadingZero = digits.length() > 1 && digits.charAt(0) == '0';
        boolean isNegativeZero = s.startsWith("-") && digits.equals("0");
        if (digits.isEmpty() || hasLeadingZero || isNegativeZero) {
            throw new NotAnIntegerException();
        }

        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw new NotAnIntegerException();
        }
    }

    public RedisString append(byte[] suffix) {
        byte[] combined = Arrays.copyOf(value, value.length + suffix.length);
        System.arraycopy(suffix, 0, combined, value.length, suffix.length);
        return new RedisString(combined);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof RedisString(byte[] other) && Arrays.equals(value, other);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(value);
    }

    @Override
    public String toString() {
        return "RedisString[" + asString() + "]";
    }
}
