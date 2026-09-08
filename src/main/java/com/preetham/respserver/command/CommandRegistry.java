package com.preetham.respserver.command;

import com.preetham.respserver.command.impl.ConnectionCommands;
import com.preetham.respserver.command.impl.GenericCommands;
import com.preetham.respserver.command.impl.StringCommands;
import com.preetham.respserver.protocol.RespValue;
import com.preetham.respserver.store.RedisDataException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Looks up commands by name and runs them, converting every failure into a RESP error
 * reply rather than letting it escape.
 *
 * <h2>Why dispatch never throws</h2>
 *
 * A Redis connection survives errors: sending {@code GET} with no arguments gets you
 * an error reply and the connection stays open for the next command. So the boundary
 * between "this command failed" and "this connection is broken" has to be drawn
 * carefully, and it is drawn here. Anything a client can cause -- bad arity, unknown
 * command, wrong type, a value that is not a number -- becomes a reply. Only a
 * malformed <em>frame</em>, which desynchronises the byte stream and is handled a
 * layer up, justifies closing the socket.
 *
 * <p>The final {@code RuntimeException} catch is deliberate. A null pointer in one
 * handler should not take down a connection, let alone the server, and on a
 * long-running process an unhandled bug in a rarely used command is exactly the sort
 * of thing that surfaces at 3am. The client gets a generic error; the details go to
 * the log rather than to the client, since exception text can leak internals.
 *
 * <p>Registration is immutable after construction and lookup is a plain
 * {@link HashMap} read, so a single registry is shared by every connection with no
 * synchronisation.
 */
public final class CommandRegistry {

    private final Map<String, CommandSpec> byName;

    private CommandRegistry(Map<String, CommandSpec> byName) {
        this.byName = Map.copyOf(byName);
    }

    /** The full command set this server supports. */
    public static CommandRegistry standard() {
        Map<String, CommandSpec> specs = new HashMap<>();
        ConnectionCommands.registerInto(specs);
        GenericCommands.registerInto(specs);
        StringCommands.registerInto(specs);
        return new CommandRegistry(specs);
    }

    public Optional<CommandSpec> lookup(String name) {
        return Optional.ofNullable(byName.get(name.toLowerCase(Locale.ROOT)));
    }

    public int size() {
        return byName.size();
    }

    /** Command names, lower-cased and sorted. Used by {@code COMMAND COUNT} and tests. */
    public Set<String> names() {
        return new TreeSet<>(byName.keySet());
    }

    /**
     * Runs one command and returns its reply.
     *
     * @return the reply, which may itself be an error; never null, never throws
     */
    public RespValue dispatch(CommandContext ctx) {
        CommandSpec spec = byName.get(ctx.name().toLowerCase(Locale.ROOT));

        if (spec == null) {
            return RespValue.error(unknownCommandMessage(ctx));
        }
        if (!spec.acceptsArgc(ctx.argc())) {
            return RespValue.error(CommandException.wrongArity(spec.name()).respError());
        }

        try {
            RespValue reply = spec.handler().execute(ctx);
            ctx.stats().commandProcessed();
            return reply;
        } catch (CommandException e) {
            return RespValue.error(e.respError());
        } catch (RedisDataException e) {
            // WRONGTYPE, non-integer values, overflow -- raised from inside the store.
            return RespValue.error(e.respError());
        } catch (RuntimeException e) {
            System.err.println("[resp-server] internal error running "
                    + spec.name() + ": " + e);
            e.printStackTrace();
            return RespValue.error("ERR internal error");
        }
    }

    /**
     * Reproduces Redis's unknown-command reply, which echoes the arguments back:
     * {@code ERR unknown command 'FOO', with args beginning with: 'bar', 'baz', }
     *
     * <p>Arguments are truncated so that a client sending a megabyte of junk cannot
     * make the server allocate a megabyte error string in response.
     */
    private static String unknownCommandMessage(CommandContext ctx) {
        StringBuilder sb = new StringBuilder()
                .append("ERR unknown command '")
                .append(truncate(ctx.arg(0)))
                .append("', with args beginning with: ");
        for (int i = 1; i < Math.min(ctx.argc(), 21); i++) {
            sb.append('\'').append(truncate(ctx.arg(i))).append("', ");
        }
        return sb.toString().replace('\r', ' ').replace('\n', ' ');
    }

    private static String truncate(String s) {
        return s.length() <= 128 ? s : s.substring(0, 128);
    }
}
