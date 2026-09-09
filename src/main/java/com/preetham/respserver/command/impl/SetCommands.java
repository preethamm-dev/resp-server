package com.preetham.respserver.command.impl;

import com.preetham.respserver.command.CommandContext;
import com.preetham.respserver.command.CommandSpec;
import com.preetham.respserver.protocol.RespValue;
import com.preetham.respserver.store.Bytes;
import com.preetham.respserver.store.RedisSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Set commands: {@code SADD}, {@code SREM}, {@code SMEMBERS}, {@code SISMEMBER},
 * {@code SCARD}.
 *
 * <p>{@code SADD} replies with the number of members that were new, so re-adding an
 * existing member replies 0. That return value is what makes {@code SADD} usable as an
 * atomic "claim this token" primitive.
 */
public final class SetCommands {

    private SetCommands() {
    }

    public static void registerInto(Map<String, CommandSpec> specs) {
        put(specs, CommandSpec.writing("SADD", -3, SetCommands::sadd));
        put(specs, CommandSpec.writing("SREM", -3, SetCommands::srem));
        put(specs, CommandSpec.readOnly("SMEMBERS", 2, SetCommands::smembers));
        put(specs, CommandSpec.readOnly("SISMEMBER", 3, SetCommands::sismember));
        put(specs, CommandSpec.readOnly("SCARD", 2, SetCommands::scard));
    }

    private static void put(Map<String, CommandSpec> specs, CommandSpec spec) {
        specs.put(spec.name().toLowerCase(Locale.ROOT), spec);
    }

    private static RespValue sadd(CommandContext ctx) {
        List<Bytes> members = ctx.argValuesFrom(2);
        int added = ctx.db().mutateCollection(
                ctx.arg(1), RedisSet.class, RedisSet::new, set -> set.add(members));
        return RespValue.integer(added);
    }

    private static RespValue srem(CommandContext ctx) {
        List<Bytes> members = ctx.argValuesFrom(2);
        int removed = ctx.db().mutateCollection(
                ctx.arg(1), RedisSet.class, RedisSet::new, set -> set.remove(members));
        return RespValue.integer(removed);
    }

    private static RespValue smembers(CommandContext ctx) {
        return ctx.db().getAs(ctx.arg(1), RedisSet.class)
                .map(set -> ListCommands.toArray(set.snapshot()))
                .orElse(RespValue.EMPTY_ARRAY);
    }

    private static RespValue sismember(CommandContext ctx) {
        Bytes member = ctx.argValue(2);
        boolean present = ctx.db().getAs(ctx.arg(1), RedisSet.class)
                .map(set -> set.contains(member))
                .orElse(false);
        return present ? RespValue.ONE : RespValue.ZERO;
    }

    private static RespValue scard(CommandContext ctx) {
        return RespValue.integer(
                ctx.db().getAs(ctx.arg(1), RedisSet.class).map(RedisSet::size).orElse(0));
    }
}
