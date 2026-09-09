package com.preetham.respserver.command.impl;

import com.preetham.respserver.command.CommandContext;
import com.preetham.respserver.command.CommandException;
import com.preetham.respserver.command.CommandSpec;
import com.preetham.respserver.protocol.RespValue;
import com.preetham.respserver.store.Bytes;
import com.preetham.respserver.store.RedisSortedSet;
import com.preetham.respserver.store.ScoredMember;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sorted-set commands: {@code ZADD}, {@code ZSCORE}, {@code ZINCRBY}, {@code ZRANGE},
 * {@code ZREVRANGE}, {@code ZRANK}, {@code ZREVRANK}, {@code ZREM}, {@code ZCARD}.
 *
 * <h2>Scores are doubles, and formatting them matters</h2>
 *
 * Redis stores scores as IEEE-754 doubles but returns them as strings, formatted so that
 * a whole number comes back as {@code "3"} rather than {@code "3.0"}. Clients parse those
 * strings, and some are strict about the shape, so {@link #formatScore} reproduces the
 * convention rather than calling {@code Double.toString} directly.
 *
 * <p>Scores also accept {@code +inf} and {@code -inf}, which is how callers express "sort
 * this to the very end" without inventing a large magic number.
 */
public final class SortedSetCommands {

    private SortedSetCommands() {
    }

    public static void registerInto(Map<String, CommandSpec> specs) {
        put(specs, CommandSpec.writing("ZADD", -4, SortedSetCommands::zadd));
        put(specs, CommandSpec.writing("ZINCRBY", 4, SortedSetCommands::zincrby));
        put(specs, CommandSpec.readOnly("ZSCORE", 3, SortedSetCommands::zscore));
        put(specs, CommandSpec.readOnly("ZRANGE", -4, ctx -> range(ctx, false)));
        put(specs, CommandSpec.readOnly("ZREVRANGE", -4, ctx -> range(ctx, true)));
        put(specs, CommandSpec.readOnly("ZRANK", 3, ctx -> rank(ctx, false)));
        put(specs, CommandSpec.readOnly("ZREVRANK", 3, ctx -> rank(ctx, true)));
        put(specs, CommandSpec.writing("ZREM", -3, SortedSetCommands::zrem));
        put(specs, CommandSpec.readOnly("ZCARD", 2, SortedSetCommands::zcard));
    }

    private static void put(Map<String, CommandSpec> specs, CommandSpec spec) {
        specs.put(spec.name().toLowerCase(Locale.ROOT), spec);
    }

    /**
     * Parses a score, accepting the infinity spellings Redis supports.
     *
     * <p>NaN is rejected: it compares false against everything including itself, so a NaN
     * score would occupy a position in the ordering that no search could ever find again.
     */
    static double parseScore(String raw) throws CommandException {
        String normalised = raw.trim().toLowerCase(Locale.ROOT);
        double score = switch (normalised) {
            case "inf", "+inf", "infinity", "+infinity" -> Double.POSITIVE_INFINITY;
            case "-inf", "-infinity" -> Double.NEGATIVE_INFINITY;
            default -> {
                try {
                    yield Double.parseDouble(raw);
                } catch (NumberFormatException e) {
                    throw new CommandException("ERR value is not a valid float");
                }
            }
        };
        if (Double.isNaN(score)) {
            throw new CommandException("ERR value is not a valid float");
        }
        return score;
    }

    /** Formats a score the way Redis does: {@code 3} rather than {@code 3.0}. */
    static String formatScore(double score) {
        if (Double.isInfinite(score)) {
            return score > 0 ? "inf" : "-inf";
        }
        if (score == Math.rint(score) && Math.abs(score) < 1e17) {
            return Long.toString((long) score);
        }
        return Double.toString(score);
    }

    /** {@code ZADD key score member [score member ...]}. Replies with the count of new members. */
    private static RespValue zadd(CommandContext ctx) throws CommandException {
        if ((ctx.argc() - 2) % 2 != 0) {
            throw CommandException.syntaxError();
        }

        // Parse every score before mutating: a bad score halfway through must not leave
        // the set half updated.
        int pairs = (ctx.argc() - 2) / 2;
        double[] scores = new double[pairs];
        List<Bytes> members = new ArrayList<>(pairs);
        for (int i = 0; i < pairs; i++) {
            scores[i] = parseScore(ctx.arg(2 + i * 2));
            members.add(ctx.argValue(3 + i * 2));
        }

        int added = ctx.db().mutateCollection(
                ctx.arg(1), RedisSortedSet.class, RedisSortedSet::new,
                zset -> {
                    int newMembers = 0;
                    for (int i = 0; i < pairs; i++) {
                        if (zset.add(members.get(i), scores[i])) {
                            newMembers++;
                        }
                    }
                    return newMembers;
                });
        return RespValue.integer(added);
    }

    private static RespValue zincrby(CommandContext ctx) throws CommandException {
        double delta = parseScore(ctx.arg(2));
        Bytes member = ctx.argValue(3);

        double updated = ctx.db().mutateCollection(
                ctx.arg(1), RedisSortedSet.class, RedisSortedSet::new,
                zset -> zset.incrementScore(member, delta));
        return RespValue.bulk(formatScore(updated));
    }

    private static RespValue zscore(CommandContext ctx) {
        Bytes member = ctx.argValue(2);
        return ctx.db().getAs(ctx.arg(1), RedisSortedSet.class)
                .map(zset -> zset.score(member))
                .map(score -> RespValue.bulk(formatScore(score)))
                .orElse(RespValue.Null.BULK);
    }

    /** {@code ZRANGE key start stop [WITHSCORES]}. */
    private static RespValue range(CommandContext ctx, boolean reverse) throws CommandException {
        if (ctx.argc() > 5) {
            throw CommandException.syntaxError();
        }
        boolean withScores = false;
        if (ctx.argc() == 5) {
            if (!ctx.argUpper(4).equals("WITHSCORES")) {
                throw CommandException.syntaxError();
            }
            withScores = true;
        }

        long start = ctx.argLong(2);
        long stop = ctx.argLong(3);
        boolean includeScores = withScores;

        return ctx.db().getAs(ctx.arg(1), RedisSortedSet.class)
                .map(zset -> toReply(zset.range(start, stop, reverse), includeScores))
                .orElse(RespValue.EMPTY_ARRAY);
    }

    /**
     * With {@code WITHSCORES} the reply interleaves member, score, member, score. RESP2
     * has no map type, so a flat array is the only option -- which is one of the things
     * RESP3 was introduced to fix.
     */
    private static RespValue toReply(List<ScoredMember> entries, boolean withScores) {
        List<RespValue> items = new ArrayList<>(withScores ? entries.size() * 2 : entries.size());
        for (ScoredMember entry : entries) {
            items.add(RespValue.bulk(entry.member().value()));
            if (withScores) {
                items.add(RespValue.bulk(formatScore(entry.score())));
            }
        }
        return RespValue.array(items);
    }

    /** Replies with a null bulk string, not an error, when the member or key is absent. */
    private static RespValue rank(CommandContext ctx, boolean reverse) {
        Bytes member = ctx.argValue(2);
        return ctx.db().getAs(ctx.arg(1), RedisSortedSet.class)
                .map(zset -> reverse ? zset.reverseRank(member) : zset.rank(member))
                .filter(rank -> rank >= 0)
                .map(RespValue::integer)
                .orElse(RespValue.Null.BULK);
    }

    private static RespValue zrem(CommandContext ctx) {
        List<Bytes> members = ctx.argValuesFrom(2);
        int removed = ctx.db().mutateCollection(
                ctx.arg(1), RedisSortedSet.class, RedisSortedSet::new,
                zset -> zset.remove(members));
        return RespValue.integer(removed);
    }

    private static RespValue zcard(CommandContext ctx) {
        return RespValue.integer(ctx.db()
                .getAs(ctx.arg(1), RedisSortedSet.class).map(RedisSortedSet::size).orElse(0));
    }
}
