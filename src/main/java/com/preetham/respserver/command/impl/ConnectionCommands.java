package com.preetham.respserver.command.impl;

import com.preetham.respserver.command.CommandContext;
import com.preetham.respserver.command.CommandException;
import com.preetham.respserver.command.CommandSpec;
import com.preetham.respserver.protocol.RespValue;
import com.preetham.respserver.stats.ServerStats;
import java.util.Locale;
import java.util.Map;

/**
 * Connection handling and introspection: {@code PING}, {@code ECHO}, {@code QUIT},
 * {@code SELECT}, {@code CLIENT}, {@code COMMAND}, {@code INFO}.
 *
 * <h2>Why COMMAND matters more than it looks</h2>
 *
 * {@code redis-cli} issues {@code COMMAND DOCS} the moment it connects, to build its
 * tab-completion table. A server that does not answer leaves the client waiting or
 * prints a warning before every session -- which is the single most common reason a
 * hand-written Redis server "doesn't work with redis-cli" despite the protocol being
 * fine. An empty array is a valid answer meaning "no documentation available", and the
 * client proceeds normally.
 *
 * <h2>Why HELLO is deliberately absent</h2>
 *
 * A client wanting RESP3 sends {@code HELLO 3}. Receiving "unknown command" is the
 * documented signal that the server predates RESP3, so the client silently stays on
 * RESP2 -- exactly what this server speaks. Implementing HELLO halfway would be worse
 * than not implementing it at all.
 */
public final class ConnectionCommands {

    private ConnectionCommands() {
    }

    public static void registerInto(Map<String, CommandSpec> specs) {
        put(specs, CommandSpec.readOnly("PING", -1, ConnectionCommands::ping));
        put(specs, CommandSpec.readOnly("ECHO", 2, ConnectionCommands::echo));
        put(specs, CommandSpec.readOnly("QUIT", 1, ConnectionCommands::quit));
        put(specs, CommandSpec.readOnly("SELECT", 2, ConnectionCommands::select));
        put(specs, CommandSpec.readOnly("COMMAND", -1, ConnectionCommands::command));
        put(specs, CommandSpec.readOnly("CLIENT", -2, ConnectionCommands::client));
        put(specs, CommandSpec.readOnly("INFO", -1, ConnectionCommands::info));
    }

    private static void put(Map<String, CommandSpec> specs, CommandSpec spec) {
        specs.put(spec.name().toLowerCase(Locale.ROOT), spec);
    }

    /** {@code PING} replies {@code +PONG}; {@code PING message} echoes the message. */
    private static RespValue ping(CommandContext ctx) throws CommandException {
        return switch (ctx.argc()) {
            case 1 -> RespValue.PONG;
            case 2 -> RespValue.bulk(ctx.argBytes(1));
            default -> throw CommandException.wrongArity("ping");
        };
    }

    private static RespValue echo(CommandContext ctx) {
        return RespValue.bulk(ctx.argBytes(1));
    }

    /**
     * The {@code +OK} has to reach the client before the socket closes, so the handler
     * only records the intent; the connection loop flushes and then closes.
     */
    private static RespValue quit(CommandContext ctx) {
        ctx.session().requestClose();
        return RespValue.OK;
    }

    /**
     * Only database 0 exists. Redis ships 16 numbered databases, but they are a legacy
     * feature discouraged in modern use, and multiplying the keyspace would add
     * bookkeeping to every command for no benefit here. {@code SELECT 0} succeeds so
     * that clients which always send it still work.
     */
    private static RespValue select(CommandContext ctx) throws CommandException {
        if (ctx.argLong(1) != 0) {
            throw new CommandException("ERR DB index is out of range");
        }
        return RespValue.OK;
    }

    /** Answers {@code COMMAND}, {@code COMMAND DOCS}, {@code COMMAND COUNT} and friends. */
    private static RespValue command(CommandContext ctx) {
        if (ctx.argc() >= 2 && ctx.argUpper(1).equals("COUNT")) {
            return RespValue.integer(ctx.registry().size());
        }
        return RespValue.EMPTY_ARRAY;
    }

    private static RespValue client(CommandContext ctx) throws CommandException {
        return switch (ctx.argUpper(1)) {
            case "ID" -> RespValue.integer(ctx.session().id());
            case "GETNAME" -> {
                String name = ctx.session().name();
                yield name.isEmpty() ? RespValue.Null.BULK : RespValue.bulk(name);
            }
            case "SETNAME" -> {
                if (ctx.argc() != 3) {
                    throw CommandException.wrongArity("client|setname");
                }
                String name = ctx.arg(2);
                if (name.chars().anyMatch(c -> c == ' ' || c == '\n' || c == '\r')) {
                    throw new CommandException("ERR Client names cannot contain spaces, "
                            + "newlines or special characters.");
                }
                ctx.session().setName(name);
                yield RespValue.OK;
            }
            // Accept and ignore the rest, so clients probing for features are not disrupted.
            default -> RespValue.OK;
        };
    }

    /**
     * A trimmed {@code INFO}.
     *
     * <p>{@code redis_version} is a deliberate compatibility shim: many clients gate
     * feature detection on it and misbehave against an unrecognised value. The
     * companion {@code resp_server_version} and {@code server_name} fields state what
     * this actually is. The README says the same thing plainly -- this is a
     * Redis-<em>compatible</em> server, not Redis.
     */
    private static RespValue info(CommandContext ctx) {
        ServerStats stats = ctx.stats();
        String text = String.join("\r\n",
                "# Server",
                "redis_version:7.0.0",
                "server_name:resp-server",
                "resp_server_version:0.2.0",
                "os:" + System.getProperty("os.name"),
                "arch_bits:64",
                "process_id:" + ProcessHandle.current().pid(),
                "uptime_in_seconds:" + stats.uptimeSeconds(),
                "java_version:" + System.getProperty("java.version"),
                "",
                "# Clients",
                "connected_clients:" + stats.connectedClients(),
                "",
                "# Stats",
                "total_connections_received:" + stats.totalConnections(),
                "total_commands_processed:" + stats.totalCommands(),
                "rejected_connections:" + stats.rejectedConnections(),
                "protocol_errors:" + stats.protocolErrors(),
                "",
                "# Keyspace",
                "db0:keys=" + ctx.db().size(),
                "");
        return RespValue.bulk(text);
    }
}
