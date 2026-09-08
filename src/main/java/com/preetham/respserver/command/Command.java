package com.preetham.respserver.command;

import com.preetham.respserver.protocol.RespValue;

/**
 * A single command handler.
 *
 * <p>Handlers are pure functions from arguments to a reply: they read and write the
 * keyspace through {@link CommandContext#db()} but hold no state of their own, so one
 * instance serves every connection and no synchronisation is needed at this layer.
 * All the concurrency lives in {@link com.preetham.respserver.store.Database}.
 */
@FunctionalInterface
public interface Command {

    /**
     * Runs the command.
     *
     * @return the reply to send; never null
     * @throws CommandException for a client-visible failure such as bad syntax
     */
    RespValue execute(CommandContext ctx) throws CommandException;
}
