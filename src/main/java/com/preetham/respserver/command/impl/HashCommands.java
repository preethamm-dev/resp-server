package com.preetham.respserver.command.impl;

import com.preetham.respserver.command.CommandContext;
import com.preetham.respserver.command.CommandException;
import com.preetham.respserver.command.CommandSpec;
import com.preetham.respserver.protocol.RespValue;
import com.preetham.respserver.store.Bytes;
import com.preetham.respserver.store.RedisHash;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Hash commands: {@code HSET}, {@code HGET}, {@code HDEL}, {@code HGETALL},
 * {@code HEXISTS}, {@code HLEN}, {@code HKEYS}, {@code HVALS}, {@code HMSET}.
 *
 * <p>{@code HSET} replies with the number of fields that were <em>added</em>, not the
 * number written, so overwriting an existing field replies 0 even though the write
 * happened. Reading that as "nothing changed" is a common mistake.
 *
 * <p>{@code HMSET} is deprecated in favour of variadic {@code HSET}, but Jedis's
 * {@code hmset()} still sends it and it replies {@code OK} rather than a count. It is
 * included for the same reason as {@code SETNX}: a server without it is not usable from
 * a real client.
 */
public final class HashCommands {

    private HashCommands() {
    }

    public static void registerInto(Map<String, CommandSpec> specs) {
        put(specs, CommandSpec.writing("HSET", -4, HashCommands::hset));
        put(specs, CommandSpec.writing("HMSET", -4, HashCommands::hmset));
        put(specs, CommandSpec.readOnly("HGET", 3, HashCommands::hget));
        put(specs, CommandSpec.writing("HDEL", -3, HashCommands::hdel));
        put(specs, CommandSpec.readOnly("HGETALL", 2, HashCommands::hgetall));
        put(specs, CommandSpec.readOnly("HEXISTS", 3, HashCommands::hexists));
        put(specs, CommandSpec.readOnly("HLEN", 2, HashCommands::hlen));
        put(specs, CommandSpec.readOnly("HKEYS", 2, HashCommands::hkeys));
        put(specs, CommandSpec.readOnly("HVALS", 2, HashCommands::hvals));
    }

    private static void put(Map<String, CommandSpec> specs, CommandSpec spec) {
        specs.put(spec.name().toLowerCase(Locale.ROOT), spec);
    }

    /** Field/value pairs, so the argument count after the key must be even. */
    private static List<Bytes> requirePairs(CommandContext ctx) throws CommandException {
        if ((ctx.argc() - 2) % 2 != 0) {
            throw CommandException.wrongArity(ctx.name());
        }
        return ctx.argValuesFrom(2);
    }

    private static RespValue hset(CommandContext ctx) throws CommandException {
        List<Bytes> pairs = requirePairs(ctx);
        int added = ctx.db().mutateCollection(
                ctx.arg(1), RedisHash.class, RedisHash::new, hash -> hash.put(pairs));
        return RespValue.integer(added);
    }

    private static RespValue hmset(CommandContext ctx) throws CommandException {
        List<Bytes> pairs = requirePairs(ctx);
        ctx.db().mutateCollection(
                ctx.arg(1), RedisHash.class, RedisHash::new, hash -> hash.put(pairs));
        return RespValue.OK;
    }

    private static RespValue hget(CommandContext ctx) {
        Bytes field = ctx.argValue(2);
        return ctx.db().getAs(ctx.arg(1), RedisHash.class)
                .map(hash -> hash.get(field))
                .map(value -> RespValue.bulk(value.value()))
                .orElse(RespValue.Null.BULK);
    }

    private static RespValue hdel(CommandContext ctx) {
        List<Bytes> fields = ctx.argValuesFrom(2);
        int removed = ctx.db().mutateCollection(
                ctx.arg(1), RedisHash.class, RedisHash::new, hash -> hash.remove(fields));
        return RespValue.integer(removed);
    }

    private static RespValue hgetall(CommandContext ctx) {
        return ctx.db().getAs(ctx.arg(1), RedisHash.class)
                .map(hash -> ListCommands.toArray(hash.flattened()))
                .orElse(RespValue.EMPTY_ARRAY);
    }

    private static RespValue hexists(CommandContext ctx) {
        Bytes field = ctx.argValue(2);
        boolean present = ctx.db().getAs(ctx.arg(1), RedisHash.class)
                .map(hash -> hash.contains(field))
                .orElse(false);
        return present ? RespValue.ONE : RespValue.ZERO;
    }

    private static RespValue hlen(CommandContext ctx) {
        return RespValue.integer(
                ctx.db().getAs(ctx.arg(1), RedisHash.class).map(RedisHash::size).orElse(0));
    }

    private static RespValue hkeys(CommandContext ctx) {
        return ctx.db().getAs(ctx.arg(1), RedisHash.class)
                .map(hash -> ListCommands.toArray(hash.fieldNames()))
                .orElse(RespValue.EMPTY_ARRAY);
    }

    private static RespValue hvals(CommandContext ctx) {
        return ctx.db().getAs(ctx.arg(1), RedisHash.class)
                .map(hash -> ListCommands.toArray(hash.values()))
                .orElse(RespValue.EMPTY_ARRAY);
    }
}
