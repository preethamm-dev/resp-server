package com.preetham.respserver;

import com.preetham.respserver.config.ServerConfig;
import org.junit.jupiter.api.DisplayName;

/**
 * Runs the full compatibility suite against the single-threaded NIO event loop.
 *
 * <p>Identical assertions to {@code VirtualThreadServerIT}, and that is the point: the
 * two servers share the command layer and the keyspace but nothing below the transport,
 * so only a test that exercises both proves they are interchangeable. Without it, the
 * benchmark comparing them would be comparing two subtly different servers.
 *
 * <p>Event-loop-specific hazards -- partial writes, large replies, many concurrent
 * clients, half-closed sockets -- are covered separately in {@code EventLoopStressIT}.
 */
@DisplayName("compatibility: event loop")
class EventLoopServerIT extends CompatibilitySuite {

    @Override
    protected ServerConfig.ServerMode mode() {
        return ServerConfig.ServerMode.EVENT_LOOP;
    }
}
