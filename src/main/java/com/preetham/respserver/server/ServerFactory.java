package com.preetham.respserver.server;

import com.preetham.respserver.command.CommandRegistry;
import com.preetham.respserver.config.ServerConfig;
import com.preetham.respserver.stats.ServerStats;
import com.preetham.respserver.store.Database;

/**
 * Builds the server implementation named by {@link ServerConfig#mode()}.
 *
 * <p>Everything except the transport is passed in from outside, which is what keeps the
 * two implementations genuinely comparable: same keyspace, same command registry, same
 * counters. The factory exists so that callers -- {@code Main}, and every integration
 * test -- select a mode by configuration rather than by choosing a class, which is why
 * the whole test suite can be run twice, once per mode, from one parameterised base.
 */
public final class ServerFactory {

    private ServerFactory() {
    }

    public static RedisServer create(ServerConfig config,
                                     Database database,
                                     CommandRegistry registry,
                                     ServerStats stats) {
        return switch (config.mode()) {
            case VIRTUAL_THREADS -> new VirtualThreadServer(config, database, registry, stats);
            case EVENT_LOOP -> throw new UnsupportedOperationException(
                    "the event-loop server is not implemented yet; use --mode virtual");
        };
    }
}
