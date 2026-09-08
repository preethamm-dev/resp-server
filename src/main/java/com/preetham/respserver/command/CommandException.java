package com.preetham.respserver.command;

/**
 * A command-level failure that maps onto a RESP error reply -- wrong arity, unknown
 * command, bad syntax, an argument that should have been a number.
 *
 * <p>Carries the complete reply text including the error code, for the same reason as
 * {@link com.preetham.respserver.store.RedisDataException}: clients branch on the code,
 * so {@code ERR} and {@code WRONGTYPE} are not interchangeable.
 *
 * <p>Checked rather than unchecked, because a command handler failing is an expected
 * outcome on a server that accepts arbitrary input, and the compiler should make sure
 * the dispatch loop handles it.
 */
public class CommandException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String respError;

    public CommandException(String respError) {
        super(respError);
        this.respError = respError;
    }

    public String respError() {
        return respError;
    }

    public static CommandException wrongArity(String commandName) {
        return new CommandException(
                "ERR wrong number of arguments for '" + commandName.toLowerCase() + "' command");
    }

    public static CommandException syntaxError() {
        return new CommandException("ERR syntax error");
    }

    public static CommandException notAnInteger() {
        return new CommandException("ERR value is not an integer or out of range");
    }

    public static CommandException invalidExpireTime(String commandName) {
        return new CommandException(
                "ERR invalid expire time in '" + commandName.toLowerCase() + "' command");
    }
}
