package com.preetham.respserver.store;

/**
 * A sorted-set member together with its score.
 *
 * <p>This exists so that {@link RedisSortedSet} can expose range results without exposing
 * {@link SkipList}, which is deliberately package-private: which ordered structure backs
 * a sorted set is an implementation detail, and swapping it for a B-tree or a balanced
 * tree should not ripple into the command layer.
 */
public record ScoredMember(Bytes member, double score) {
}
