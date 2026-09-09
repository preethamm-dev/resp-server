package com.preetham.respserver.command;

import com.preetham.respserver.persistence.AofWriter;
import com.preetham.respserver.protocol.RespValue;
import com.preetham.respserver.stats.ServerStats;
import com.preetham.respserver.store.Database;
import java.util.List;
import java.util.Optional;

/**
 * Runs a command and, when persistence is enabled, records it in the append-only file.
 *
 * <h2>Why persistence lives here and not in the registry</h2>
 *
 * The registry is a shared, immutable lookup table with no per-server state. Threading a
 * writer through it would make it stateful and would put an I/O concern inside the thing
 * whose job is dispatch. This thin layer keeps the registry pure and gives the two
 * servers a single place to hook.
 *
 * <h2>The rule for what gets persisted</h2>
 *
 * A command is appended only when both hold:
 *
 * <ul>
 *   <li>it is declared a write in its {@link CommandSpec}, and</li>
 *   <li>the keyspace's dirty counter actually moved.</li>
 * </ul>
 *
 * The second condition is the one that matters. {@code SET k v NX} against an existing key
 * changes nothing, but replaying it into an empty keyspace at startup would create the
 * key -- so recording it would make recovery produce a <em>different</em> dataset from the
 * one that was saved. Comparing the counter across the call catches every such case
 * without the executor needing to know anything about individual commands.
 *
 * <p>Recording happens after execution, so a command that fails is never persisted.
 */
public final class CommandExecutor {

    private final CommandRegistry registry;
    private final Database database;
    private final ServerStats stats;
    private final AofWriter aof;

    public CommandExecutor(CommandRegistry registry,
                           Database database,
                           ServerStats stats,
                           AofWriter aof) {
        this.registry = registry;
        this.database = database;
        this.stats = stats;
        this.aof = aof;
    }

    public RespValue execute(List<RespValue.BulkString> args, ClientSession session) {
        CommandContext ctx = new CommandContext(args, database, session, stats, registry);

        if (aof == null) {
            return registry.dispatch(ctx);
        }

        Optional<CommandSpec> spec = registry.lookup(ctx.arg(0));
        boolean isWrite = spec.map(CommandSpec::write).orElse(false);

        long dirtyBefore = isWrite ? database.dirtyCount() : 0;
        RespValue reply = registry.dispatch(ctx);

        if (isWrite && database.dirtyCount() != dirtyBefore) {
            aof.append(args);
        }
        return reply;
    }

    public CommandRegistry registry() {
        return registry;
    }
}
