package com.preetham.respserver.store;

/**
 * A value stored under a key.
 *
 * <p>Redis is not a plain string-to-string map: every key holds a typed value, and
 * most commands only accept one type. {@code LPUSH} against a key holding a string
 * is an error, not a coercion. Modelling that as a sealed hierarchy lets the
 * command layer pattern-match exhaustively, so a new type cannot be added without
 * the compiler pointing at every place that must handle it.
 *
 * <p>Implementations are responsible for their own thread safety. The keyspace
 * itself is a concurrent map, so two threads touching <em>different</em> keys never
 * contend; two threads touching the <em>same</em> key are serialised by that value's
 * own lock. This is the trade the virtual-thread server makes and the event-loop
 * server does not have to -- with a single thread there is nothing to synchronise.
 */
public sealed interface RedisObject
        permits RedisString, RedisList, RedisHash, RedisSet, RedisSortedSet {

    /** The name reported by the {@code TYPE} command, e.g. {@code "string"}. */
    String typeName();

    /**
     * Whether this value has become empty and its key should therefore disappear.
     *
     * <p>Redis deletes a collection key the moment it empties: popping the last element
     * of a list leaves no key behind, and {@code EXISTS} then replies 0. There is no such
     * thing as an empty list. Strings are the exception -- {@code SET k ""} is a live key
     * holding an empty value -- so {@link RedisString} always reports false.
     */
    boolean isEmpty();
}
