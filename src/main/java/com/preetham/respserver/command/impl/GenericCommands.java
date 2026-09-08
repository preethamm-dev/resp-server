package com.preetham.respserver.command.impl;

import com.preetham.respserver.command.CommandContext;
import com.preetham.respserver.command.CommandException;
import com.preetham.respserver.command.CommandSpec;
import com.preetham.respserver.protocol.RespValue;
import com.preetham.respserver.store.Database;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Type-agnostic key commands: existence, deletion, type inspection and expiry.
 *
 * <h2>On TTL's three-way reply</h2>
 *
 * {@code TTL} returns a positive number of seconds, {@code -1} when the key exists but
 * has no expiry, or {@code -2} when the key does not exist. Overloading a single
 * integer with two sentinel values is not a design anyone would choose today, but it is
 * what the protocol specifies and clients depend on it, so it is reproduced exactly.
 *
 * <h2>Seconds versus milliseconds</h2>
 *
 * {@code EXPIRE} takes seconds and {@code PEXPIRE} milliseconds; likewise {@code TTL}
 * and {@code PTTL}. Internally everything is milliseconds and the second-granularity
 * commands convert at the edge, so there is exactly one representation of time in the
 * store. {@code TTL} rounds up, matching Redis: a key with 1500ms left reports 2
 * seconds rather than 1, because reporting 1 would let a client conclude the key has
 * less life than it does.
 */
public final class GenericCommands {

    private GenericCommands() {
    }

    public static void registerInto(Map<String, CommandSpec> specs) {
        put(specs, CommandSpec.writing("DEL", -2, GenericCommands::del));
        put(specs, CommandSpec.readOnly("EXISTS", -2, GenericCommands::exists));
        put(specs, CommandSpec.readOnly("TYPE", 2, GenericCommands::type));
        put(specs, CommandSpec.readOnly("KEYS", 2, GenericCommands::keys));
        put(specs, CommandSpec.readOnly("DBSIZE", 1, GenericCommands::dbsize));
        put(specs, CommandSpec.writing("FLUSHALL", -1, GenericCommands::flushall));
        put(specs, CommandSpec.writing("EXPIRE", -3, ctx -> expire(ctx, 1000L)));
        put(specs, CommandSpec.writing("PEXPIRE", -3, ctx -> expire(ctx, 1L)));
        put(specs, CommandSpec.readOnly("TTL", 2, ctx -> ttl(ctx, true)));
        put(specs, CommandSpec.readOnly("PTTL", 2, ctx -> ttl(ctx, false)));
        put(specs, CommandSpec.writing("PERSIST", 2, GenericCommands::persist));
    }

    private static void put(Map<String, CommandSpec> specs, CommandSpec spec) {
        specs.put(spec.name().toLowerCase(Locale.ROOT), spec);
    }

    private static RespValue del(CommandContext ctx) {
        List<String> keys = new ArrayList<>(ctx.argc() - 1);
        for (int i = 1; i < ctx.argc(); i++) {
            keys.add(ctx.arg(i));
        }
        return RespValue.integer(ctx.db().delete(keys));
    }

    /** Counts each occurrence, so {@code EXISTS k k} on an existing key replies 2. */
    private static RespValue exists(CommandContext ctx) {
        long found = 0;
        for (int i = 1; i < ctx.argc(); i++) {
            if (ctx.db().exists(ctx.arg(i))) {
                found++;
            }
        }
        return RespValue.integer(found);
    }

    /** Replies with the type name, or the simple string {@code none} for a missing key. */
    private static RespValue type(CommandContext ctx) {
        return RespValue.simple(ctx.db().type(ctx.arg(1)).orElse("none"));
    }

    private static RespValue keys(CommandContext ctx) {
        List<String> matched = ctx.db().keys(ctx.arg(1));
        List<RespValue> reply = new ArrayList<>(matched.size());
        for (String key : matched) {
            reply.add(RespValue.bulk(key));
        }
        return RespValue.array(reply);
    }

    private static RespValue dbsize(CommandContext ctx) {
        return RespValue.integer(ctx.db().size());
    }

    private static RespValue flushall(CommandContext ctx) {
        ctx.db().flushAll();
        return RespValue.OK;
    }

    /**
     * {@code EXPIRE key seconds} / {@code PEXPIRE key milliseconds}.
     *
     * <p>A non-positive TTL is not an error: Redis deletes the key immediately, since
     * "expire in -1 seconds" means it should already be gone.
     *
     * @param unitMillis how many milliseconds one unit of the argument represents
     */
    private static RespValue expire(CommandContext ctx, long unitMillis)
            throws CommandException {
        String key = ctx.arg(1);
        long amount = ctx.argLong(2);

        if (!ctx.db().exists(key)) {
            return RespValue.ZERO;
        }
        if (amount <= 0) {
            ctx.db().delete(key);
            return RespValue.ONE;
        }

        long deadline;
        try {
            deadline = Math.addExact(ctx.db().now(), Math.multiplyExact(amount, unitMillis));
        } catch (ArithmeticException e) {
            throw CommandException.invalidExpireTime(ctx.name());
        }
        return ctx.db().expireAt(key, deadline) ? RespValue.ONE : RespValue.ZERO;
    }

    /**
     * {@code TTL} (seconds, rounded up) or {@code PTTL} (milliseconds).
     * Both pass the {@code -1} / {@code -2} sentinels through unchanged.
     */
    private static RespValue ttl(CommandContext ctx, boolean inSeconds) {
        long millis = ctx.db().ttlMillis(ctx.arg(1));
        if (millis == Database.TTL_KEY_NOT_FOUND || millis == Database.TTL_NO_EXPIRY) {
            return RespValue.integer(millis);
        }
        return RespValue.integer(inSeconds ? (millis + 999) / 1000 : millis);
    }

    private static RespValue persist(CommandContext ctx) {
        return ctx.db().persist(ctx.arg(1)) ? RespValue.ONE : RespValue.ZERO;
    }
}
