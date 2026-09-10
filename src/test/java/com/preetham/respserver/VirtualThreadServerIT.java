package com.preetham.respserver;

import com.preetham.respserver.config.ServerConfig;
import org.junit.jupiter.api.DisplayName;

/** Runs the full compatibility suite against the virtual-thread-per-connection server. */
@DisplayName("compatibility: virtual threads")
class VirtualThreadServerIT extends CompatibilitySuite {

    @Override
    protected ServerConfig.ServerMode mode() {
        return ServerConfig.ServerMode.VIRTUAL_THREADS;
    }
}
