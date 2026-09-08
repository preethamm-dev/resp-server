package com.preetham.respserver.command.impl;

import com.preetham.respserver.command.CommandContext;
import com.preetham.respserver.command.CommandException;
import com.preetham.respserver.command.CommandSpec;
import com.preetham.respserver.protocol.RespValue;
import com.preetham.respserver.store.RedisString;
import com.preetham.respserver.store.ValueHolder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * String commands: {@code SET}, {@code GET}, {@code MSET}, {@code MGET},
 * {@code INCR}/{@code DECR} family, {@code APPEND}, {@code STRLEN}, {@code GETDEL}.
 *
 * <h2>SET is not a simple command</h2>
 *
 * {@code SET key value [EX s|PX ms] [NX|XX] [KEEPTTL]} has to be parsed properly:
 * options may appear in any order, {@code NX} and {@code XX} are mutually exclusive, and
 * {@code EX} and {@code PX} are too. A plain {@code SET} also <em>clears</em> any
 * existing TTL, which surprises people -- {@code KEEPTTL} exists precisely because that
 * default bites.
 *
 * <h2>Atomicity boundary</h2>
 *
 * Single-key commands here are atomic because the store performs read-modify-write
 * inside {@code ConcurrentHashMap.compute}. Multi-key commands are <em>not</em>:
 * {@code MSET} writes its keys one at a time, so a concurrent reader can observe a
 * partially applied batch. Real Redis makes {@code MSET} atomic by virtue of being
 * single-threaded. That difference is a genuine limitation of the virtual-thread
 * design and is recorded in the README rather than papered over -- making it atomic
 * would need a global lock, which would cost far more than it buys.
 */
public final class StringCommands {

    private StringCommands() {
    }

    public static void registerInto(Map<String, CommandSpec> specs) {
        put(specs, CommandSpec.writing("SET", -3, StringCommands::set));
        put(specs, CommandSpec.readOnly("GET", 2, StringCommands::get));
        put(specs, CommandSpec.writing("GETDEL", 2, StringCommands::getdel));
        put(specs, CommandSpec.writing("GETSET", 3, StringCommands::getset));
        // The legacy standalone forms. SET gained NX/EX options in Redis 2.6.12 and
        // these are formally deprecated, but real clients still send them: Jedis maps
        // setnx() and setex() straight onto them, so a server without them is not
        // usable from Jedis regardless of how good its SET is.
        put(specs, CommandSpec.writing("SETNX", 3, StringCommands::setnx));
        put(specs, CommandSpec.writing("SETEX", 4, ctx -> setex(ctx, 1000L)));
        put(specs, CommandSpec.writing("PSETEX", 4, ctx -> setex(ctx, 1L)));
        put(specs, CommandSpec.writing("MSET", -3, StringCommands::mset));
        put(specs, CommandSpec.readOnly("MGET", -2, StringCommands::mget));
        put(specs, CommandSpec.writing("INCR", 2, ctx -> incrBy(ctx, 1)));
        put(specs, CommandSpec.writing("DECR", 2, ctx -> incrBy(ctx, -1)));
        put(specs, CommandSpec.writing("INCRBY", 3, ctx -> incrBy(ctx, ctx.argLong(2))));
        put(specs, CommandSpec.writing("DECRBY", 3, StringCommands::decrBy));
        put(specs, CommandSpec.writing("APPEND", 3, StringCommands::append));
        put(specs, CommandSpec.readOnly("STRLEN", 2, StringCommands::strlen));
    }

    private static void put(Map<String, CommandSpec> specs, CommandSpec spec) {
        specs.put(spec.name().toLowerCase(Locale.ROOT), spec);
    }

    /** Parsed form of {@code SET}'s optional arguments. */
    private record SetOptions(long expireAtMillis, boolean nx, boolean xx, boolean keepTtl) {
    }

    private static RespValue set(CommandContext ctx) throws CommandException {
        String key = ctx.arg(1);
        byte[] value = ctx.argBytes(2);
        SetOptions options = parseSetOptions(ctx);

        RedisString stored = new RedisString(value);

        if (options.nx()) {
            boolean written = ctx.db().setIfAbsent(key, stored, options.expireAtMillis());
            // A failed conditional SET replies with a null bulk string, not an error:
            // "I did nothing" is a normal outcome, not a fault.
            return written ? RespValue.OK : RespValue.Null.BULK;
        }
        if (options.xx()) {
            boolean written = ctx.db().setIfPresent(key, stored, options.expireAtMillis());
            return written ? RespValue.OK : RespValue.Null.BULK;
        }

        if (options.keepTtl()) {
            long existingTtl = ctx.db().ttlMillis(key);
            long deadline = existingTtl >= 0
                    ? ctx.db().now() + existingTtl
                    : ValueHolder.NO_EXPIRY;
            ctx.db().set(key, stored, deadline);
        } else {
            ctx.db().set(key, stored, options.expireAtMillis());
        }
        return RespValue.OK;
    }

    private static SetOptions parseSetOptions(CommandContext ctx) throws CommandException {
        long expireAt = ValueHolder.NO_EXPIRY;
        boolean nx = false;
        boolean xx = false;
        boolean keepTtl = false;
        boolean expirySeen = false;

        int i = 3;
        while (i < ctx.argc()) {
            String option = ctx.argUpper(i);
            switch (option) {
                case "NX" -> {
                    if (xx) {
                        throw CommandException.syntaxError();
                    }
                    nx = true;
                    i++;
                }
                case "XX" -> {
                    if (nx) {
                        throw CommandException.syntaxError();
                    }
                    xx = true;
                    i++;
                }
                case "KEEPTTL" -> {
                    if (expirySeen) {
                        throw CommandException.syntaxError();
                    }
                    keepTtl = true;
                    i++;
                }
                case "EX", "PX" -> {
                    if (expirySeen || keepTtl || i + 1 >= ctx.argc()) {
                        throw CommandException.syntaxError();
                    }
                    long amount = ctx.argLong(i + 1);
                    if (amount <= 0) {
                        throw CommandException.invalidExpireTime("set");
                    }
                    long unit = option.equals("EX") ? 1000L : 1L;
                    try {
                        expireAt = Math.addExact(ctx.db().now(), Math.multiplyExact(amount, unit));
                    } catch (ArithmeticException e) {
                        throw CommandException.invalidExpireTime("set");
                    }
                    expirySeen = true;
                    i += 2;
                }
                default -> throw CommandException.syntaxError();
            }
        }
        return new SetOptions(expireAt, nx, xx, keepTtl);
    }

    private static RespValue get(CommandContext ctx) {
        return ctx.db().getString(ctx.arg(1))
                .map(s -> RespValue.bulk(s.value()))
                .orElse(RespValue.Null.BULK);
    }

    private static RespValue getdel(CommandContext ctx) {
        String key = ctx.arg(1);
        // getString first so a wrong-type key errors instead of being silently deleted.
        RespValue reply = ctx.db().getString(key)
                .map(s -> RespValue.bulk(s.value()))
                .orElse(RespValue.Null.BULK);
        ctx.db().delete(key);
        return reply;
    }

    /**
     * {@code SETNX key value} -- the pre-2.6.12 spelling of {@code SET key value NX}.
     * Note the different reply shape: an integer 1/0 rather than {@code OK}/nil.
     */
    private static RespValue setnx(CommandContext ctx) {
        boolean written = ctx.db().setIfAbsent(
                ctx.arg(1), new RedisString(ctx.argBytes(2)), ValueHolder.NO_EXPIRY);
        return written ? RespValue.ONE : RespValue.ZERO;
    }

    /**
     * {@code SETEX key seconds value} / {@code PSETEX key milliseconds value}.
     *
     * <p>Mind the argument order: the TTL comes <em>before</em> the value, unlike
     * {@code SET key value EX seconds}. A zero or negative TTL is an error here rather
     * than an immediate delete, which is how Redis distinguishes it from {@code EXPIRE}.
     *
     * @param unitMillis milliseconds represented by one unit of the TTL argument
     */
    private static RespValue setex(CommandContext ctx, long unitMillis) throws CommandException {
        String key = ctx.arg(1);
        long amount = ctx.argLong(2);
        byte[] value = ctx.argBytes(3);

        if (amount <= 0) {
            throw CommandException.invalidExpireTime(ctx.name());
        }
        long deadline;
        try {
            deadline = Math.addExact(ctx.db().now(), Math.multiplyExact(amount, unitMillis));
        } catch (ArithmeticException e) {
            throw CommandException.invalidExpireTime(ctx.name());
        }

        ctx.db().set(key, new RedisString(value), deadline);
        return RespValue.OK;
    }

    /** {@code GETSET key value} -- set, returning whatever was there before. */
    private static RespValue getset(CommandContext ctx) {
        String key = ctx.arg(1);
        RespValue previous = ctx.db().getString(key)
                .map(s -> RespValue.bulk(s.value()))
                .orElse(RespValue.Null.BULK);
        ctx.db().set(key, new RedisString(ctx.argBytes(2)));
        return previous;
    }

    private static RespValue mset(CommandContext ctx) throws CommandException {
        // Key/value pairs, so the argument count after MSET must be even.
        if ((ctx.argc() - 1) % 2 != 0) {
            throw CommandException.wrongArity("mset");
        }
        for (int i = 1; i < ctx.argc(); i += 2) {
            ctx.db().set(ctx.arg(i), new RedisString(ctx.argBytes(i + 1)));
        }
        return RespValue.OK;
    }

    /**
     * {@code MGET} never fails on type: a key holding a list yields a null in that
     * position rather than a {@code WRONGTYPE} error for the whole call. That is Redis's
     * behaviour and it is deliberate -- one odd key should not spoil a bulk read.
     */
    private static RespValue mget(CommandContext ctx) {
        List<RespValue> replies = new ArrayList<>(ctx.argc() - 1);
        for (int i = 1; i < ctx.argc(); i++) {
            RespValue item;
            try {
                item = ctx.db().getString(ctx.arg(i))
                        .map(s -> RespValue.bulk(s.value()))
                        .orElse(RespValue.Null.BULK);
            } catch (com.preetham.respserver.store.WrongTypeException e) {
                item = RespValue.Null.BULK;
            }
            replies.add(item);
        }
        return RespValue.array(replies);
    }

    private static RespValue incrBy(CommandContext ctx, long delta) {
        return RespValue.integer(ctx.db().incrBy(ctx.arg(1), delta));
    }

    /**
     * {@code DECRBY key n} is {@code INCRBY key -n}, except at {@link Long#MIN_VALUE}
     * where negation overflows. Redis rejects that rather than wrapping.
     */
    private static RespValue decrBy(CommandContext ctx) throws CommandException {
        long amount = ctx.argLong(2);
        if (amount == Long.MIN_VALUE) {
            throw new CommandException("ERR decrement would overflow");
        }
        return RespValue.integer(ctx.db().incrBy(ctx.arg(1), -amount));
    }

    private static RespValue append(CommandContext ctx) {
        return RespValue.integer(ctx.db().append(ctx.arg(1), ctx.argBytes(2)));
    }

    private static RespValue strlen(CommandContext ctx) {
        return RespValue.integer(
                ctx.db().getString(ctx.arg(1)).map(RedisString::length).orElse(0));
    }
}
