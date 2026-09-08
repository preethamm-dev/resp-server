package com.preetham.respserver.server;

import java.io.IOException;

/**
 * A running server, independent of how it handles concurrency.
 *
 * <p>This interface is the seam that makes the project's central comparison honest.
 * The virtual-thread server and the event-loop server implement it over the same
 * command registry and the same keyspace, so a benchmark between them isolates exactly
 * one variable: how bytes get on and off the socket. Anything else -- a different
 * command implementation, a different store -- would make the numbers meaningless.
 *
 * <p>{@link #start()} binds and returns; it does not block. Tests bind port 0 and read
 * the real port back from {@link #port()}, which avoids both hard-coded ports and the
 * flakiness of guessing a free one.
 */
public interface RedisServer extends AutoCloseable {

    /** Binds the listening socket and begins accepting. Returns once bound. */
    void start() throws IOException;

    /** The bound port. Meaningful only after {@link #start()}, and resolves port 0. */
    int port();

    /** Whether the server is currently accepting connections. */
    boolean isRunning();

    /** Stops accepting and closes existing connections. Idempotent. */
    @Override
    void close();
}
