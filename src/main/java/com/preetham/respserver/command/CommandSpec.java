package com.preetham.respserver.command;

/**
 * Registry entry for one command.
 *
 * <p>{@code arity} follows Redis's own convention, which encodes two rules in one
 * number and includes the command name in the count:
 * <ul>
 *   <li>positive -- exactly this many arguments ({@code GET key} is 2)</li>
 *   <li>negative -- at least this many ({@code DEL key [key ...]} is -2)</li>
 * </ul>
 * Matching Redis here is not cosmetic: it means the arity check and the error text
 * behave identically to the real server, which is what the compatibility tests assert.
 *
 * <p>{@code write} marks commands that mutate the keyspace. Nothing uses it yet; the
 * append-only file added on day 2 uses it to decide what to persist.
 */
public record CommandSpec(String name, int arity, boolean write, Command handler) {

    public static CommandSpec readOnly(String name, int arity, Command handler) {
        return new CommandSpec(name, arity, false, handler);
    }

    public static CommandSpec writing(String name, int arity, Command handler) {
        return new CommandSpec(name, arity, true, handler);
    }

    /** Whether {@code argc} (including the command name) satisfies this command's arity. */
    public boolean acceptsArgc(int argc) {
        return arity >= 0 ? argc == arity : argc >= -arity;
    }
}
