package com.preetham.respserver.store;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * An immutable byte string used as a list element, hash field or value, set member, or
 * sorted-set member.
 *
 * <h2>Why not just use String</h2>
 *
 * Redis is binary safe everywhere, not only in string values: a hash field or a set
 * member may contain arbitrary bytes. Decoding those through a charset is lossy --
 * invalid UTF-8 becomes U+FFFD and no longer round-trips, so two distinct members could
 * collapse into one and silently corrupt a set.
 *
 * <h2>Why not just use byte[]</h2>
 *
 * Arrays use identity equality, so {@code new byte[]{1}.equals(new byte[]{1})} is false
 * and every hash-based collection would misbehave. This wrapper supplies value
 * semantics, caches its hash (these are used as map keys on hot paths), and orders
 * lexicographically by unsigned byte -- which is what Redis uses to break ties between
 * equal scores in a sorted set.
 */
public final class Bytes implements Comparable<Bytes> {

    public static final Bytes EMPTY = new Bytes(new byte[0]);

    private final byte[] value;
    private final int hash;

    public Bytes(byte[] value) {
        this.value = value;
        this.hash = Arrays.hashCode(value);
    }

    public static Bytes of(String s) {
        return new Bytes(s.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The raw bytes. Not defensively copied: these sit on the hot path and copying every
     * access would double the cost of reading a collection. Treat as immutable.
     */
    public byte[] value() {
        return value;
    }

    public int length() {
        return value.length;
    }

    public String asString() {
        return new String(value, StandardCharsets.UTF_8);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof Bytes other
                && hash == other.hash
                && Arrays.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    /**
     * Lexicographic order treating bytes as <em>unsigned</em>.
     *
     * <p>{@link Arrays#compare(byte[], byte[])} compares them as signed, which puts any
     * byte above 0x7F before every ASCII character -- so {@code "é"} would sort before
     * {@code "a"}. Redis compares unsigned, and sorted-set tie-breaking depends on it.
     */
    @Override
    public int compareTo(Bytes other) {
        return Arrays.compareUnsigned(value, other.value);
    }

    @Override
    public String toString() {
        return asString();
    }
}
