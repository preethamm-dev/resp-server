package com.preetham.respserver.command.impl;

import com.preetham.respserver.command.CommandContext;
import com.preetham.respserver.command.CommandException;
import com.preetham.respserver.command.CommandSpec;
import com.preetham.respserver.protocol.RespValue;
import com.preetham.respserver.store.Bytes;
import com.preetham.respserver.store.RedisList;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * List commands: {@code LPUSH}, {@code RPUSH}, {@code LPOP}, {@code RPOP},
 * {@code LRANGE}, {@code LLEN}, {@code LINDEX}.
 *
 * <h2>Push order surprises people</h2>
 *
 * {@code LPUSH key a b c} does not produce {@code [a b c]}. Each element is pushed onto
 * the head in turn, so the result is {@code [c b a]}. That is not a quirk to work around
 * -- it is what makes {@code LPUSH} plus {@code RPOP} a correct FIFO queue.
 *
 * <h2>Empty lists do not exist</h2>
 *
 * Popping the last element deletes the key, so {@code EXISTS} afterwards replies 0. That
 * is handled centrally by {@code Database.mutateCollection}, which drops the mapping when
 * a mutation leaves the collection empty, rather than being re-implemented in each
 * command.
 */
public final class ListCommands {

    private ListCommands() {
    }

    public static void registerInto(Map<String, CommandSpec> specs) {
        put(specs, CommandSpec.writing("LPUSH", -3, ctx -> push(ctx, true)));
        put(specs, CommandSpec.writing("RPUSH", -3, ctx -> push(ctx, false)));
        put(specs, CommandSpec.writing("LPOP", -2, ctx -> pop(ctx, true)));
        put(specs, CommandSpec.writing("RPOP", -2, ctx -> pop(ctx, false)));
        put(specs, CommandSpec.readOnly("LRANGE", 4, ListCommands::lrange));
        put(specs, CommandSpec.readOnly("LLEN", 2, ListCommands::llen));
        put(specs, CommandSpec.readOnly("LINDEX", 3, ListCommands::lindex));
    }

    private static void put(Map<String, CommandSpec> specs, CommandSpec spec) {
        specs.put(spec.name().toLowerCase(Locale.ROOT), spec);
    }

    private static RespValue push(CommandContext ctx, boolean left) {
        List<Bytes> values = ctx.argValuesFrom(2);
        int length = ctx.db().mutateCollection(
                ctx.arg(1), RedisList.class, RedisList::new,
                list -> left ? list.leftPush(values) : list.rightPush(values));
        return RespValue.integer(length);
    }

    /**
     * {@code LPOP key [count]}.
     *
     * <p>The reply shape depends on whether {@code count} was given: a bare bulk string
     * without it, an array with it -- and, importantly, a null <em>array</em> rather than
     * a null bulk string when the key is missing and a count was supplied. Clients
     * distinguish the two, so the difference is not cosmetic.
     */
    private static RespValue pop(CommandContext ctx, boolean left) throws CommandException {
        if (ctx.argc() > 3) {
            throw CommandException.wrongArity(ctx.name());
        }
        String key = ctx.arg(1);
        boolean counted = ctx.argc() == 3;

        if (!counted) {
            Bytes popped = ctx.db().mutateCollection(
                    key, RedisList.class, RedisList::new,
                    list -> left ? list.leftPop() : list.rightPop());
            return popped == null ? RespValue.Null.BULK : RespValue.bulk(popped.value());
        }

        long count = ctx.argLong(2);
        if (count < 0) {
            throw new CommandException("ERR value is out of range, must be positive");
        }
        int limit = (int) Math.min(count, Integer.MAX_VALUE);

        List<Bytes> popped = ctx.db().mutateCollection(
                key, RedisList.class, RedisList::new,
                list -> left ? list.leftPop(limit) : list.rightPop(limit));

        if (popped.isEmpty() && !ctx.db().exists(key)) {
            return RespValue.Null.ARRAY;
        }
        return toArray(popped);
    }

    private static RespValue lrange(CommandContext ctx) throws CommandException {
        long start = ctx.argLong(2);
        long stop = ctx.argLong(3);

        return ctx.db().getAs(ctx.arg(1), RedisList.class)
                .map(list -> toArray(list.range(start, stop)))
                .orElse(RespValue.EMPTY_ARRAY);
    }

    private static RespValue llen(CommandContext ctx) {
        return RespValue.integer(
                ctx.db().getAs(ctx.arg(1), RedisList.class).map(RedisList::size).orElse(0));
    }

    private static RespValue lindex(CommandContext ctx) throws CommandException {
        long index = ctx.argLong(2);
        return ctx.db().getAs(ctx.arg(1), RedisList.class)
                .map(list -> list.get(index))
                .map(value -> RespValue.bulk(value.value()))
                .orElse(RespValue.Null.BULK);
    }

    static RespValue toArray(List<Bytes> values) {
        List<RespValue> items = new ArrayList<>(values.size());
        for (Bytes value : values) {
            items.add(RespValue.bulk(value.value()));
        }
        return RespValue.array(items);
    }
}
