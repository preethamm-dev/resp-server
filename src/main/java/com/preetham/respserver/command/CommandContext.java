package com.preetham.respserver.command;

import com.preetham.respserver.protocol.RespValue;
import com.preetham.respserver.stats.ServerStats;
import com.preetham.respserver.store.Bytes;
import com.preetham.respserver.store.Database;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Everything a command handler needs: the parsed arguments, the keyspace, and the
 * calling connection's session.
 *
 * <p>Arguments arrive as bulk strings, with element 0 being the command name itself --
 * this mirrors the wire format, where {@code SET foo bar} is the three-element array
 * {@code ["SET", "foo", "bar"]}. Handlers index from 1.
 *
 * <p>The typed accessors exist so that every handler produces the <em>same</em> error
 * text for the same mistake. Left to themselves, twenty handlers would invent twenty
 * slightly different messages, and clients that match on those strings would break.
 */
public final class CommandContext {

    private final List<RespValue.BulkString> args;
    private final Database database;
    private final ClientSession session;
    private final ServerStats stats;
    private final CommandRegistry registry;

    public CommandContext(List<RespValue.BulkString> args,
                          Database database,
                          ClientSession session,
                          ServerStats stats,
                          CommandRegistry registry) {
        this.args = args;
        this.database = database;
        this.session = session;
        this.stats = stats;
        this.registry = registry;
    }

    public Database db() {
        return database;
    }

    /** The registry that dispatched this command, so introspection commands can read it. */
    public CommandRegistry registry() {
        return registry;
    }

    public ClientSession session() {
        return session;
    }

    public ServerStats stats() {
        return stats;
    }

    public List<RespValue.BulkString> args() {
        return args;
    }

    /** Total argument count including the command name, matching Redis's {@code argc}. */
    public int argc() {
        return args.size();
    }

    /** The command name as written by the client, upper-cased. */
    public String name() {
        return arg(0).toUpperCase(Locale.ROOT);
    }

    /** Argument {@code i} decoded as UTF-8. Use for keys and human-readable values. */
    public String arg(int i) {
        return new String(args.get(i).value(), StandardCharsets.UTF_8);
    }

    /** Argument {@code i} upper-cased, for matching option keywords such as {@code EX}. */
    public String argUpper(int i) {
        return arg(i).toUpperCase(Locale.ROOT);
    }

    /** Raw bytes of argument {@code i}. Use for values, which are binary safe. */
    public byte[] argBytes(int i) {
        return args.get(i).value();
    }

    /** Argument {@code i} as a binary-safe value, for collection members and fields. */
    public Bytes argValue(int i) {
        return new Bytes(args.get(i).value());
    }

    /** Arguments from {@code first} to the end, as binary-safe values. */
    public List<Bytes> argValuesFrom(int first) {
        List<Bytes> values = new ArrayList<>(args.size() - first);
        for (int i = first; i < args.size(); i++) {
            values.add(new Bytes(args.get(i).value()));
        }
        return values;
    }

    /**
     * Argument {@code i} as a 64-bit integer.
     *
     * @throws CommandException with Redis's standard message if it is not a number
     */
    public long argLong(int i) throws CommandException {
        try {
            return Long.parseLong(arg(i));
        } catch (NumberFormatException e) {
            throw CommandException.notAnInteger();
        }
    }

    public boolean hasArg(int i) {
        return i < args.size();
    }
}
