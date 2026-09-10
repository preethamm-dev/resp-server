package com.preetham.respserver;

import com.preetham.respserver.command.CommandExecutor;
import com.preetham.respserver.command.CommandRegistry;
import com.preetham.respserver.config.ServerConfig;
import com.preetham.respserver.persistence.AofLoader;
import com.preetham.respserver.persistence.AofWriter;
import com.preetham.respserver.server.RedisServer;
import com.preetham.respserver.server.ServerFactory;
import com.preetham.respserver.stats.ServerStats;
import com.preetham.respserver.store.Database;
import com.preetham.respserver.store.ExpiryManager;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;

/** Entry point: parses configuration, restores any saved data, starts a server. */
public final class Main {

    public static void main(String[] args) {
        ServerConfig config;
        try {
            config = ServerConfig.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("resp-server: " + e.getMessage());
            System.err.println();
            ServerConfig.printUsage();
            System.exit(64); // EX_USAGE
            return;
        }

        Database database = new Database();
        CommandRegistry registry = CommandRegistry.standard();
        ServerStats stats = new ServerStats();

        // Replay before the writer is opened, so recovery does not re-append everything
        // it just read back into the log.
        AofLoader.Result restored = null;
        if (config.persistenceEnabled()) {
            try {
                restored = AofLoader.load(config.aofPath(), database, registry);
            } catch (IOException e) {
                System.err.println("resp-server: could not read the append-only file "
                        + config.aofPath() + " -- " + e.getMessage());
                System.exit(70); // EX_SOFTWARE
                return;
            }
        }

        AofWriter aof = null;
        if (config.persistenceEnabled()) {
            try {
                aof = new AofWriter(config.aofPath(), config.fsyncPolicy());
            } catch (IOException e) {
                System.err.println("resp-server: could not open the append-only file "
                        + config.aofPath() + " -- " + e.getMessage());
                System.exit(70);
                return;
            }
        }

        ExpiryManager expiry = new ExpiryManager(database);
        expiry.start(Duration.ofMillis(config.expiryIntervalMillis()));

        // Wire live counters into INFO. Suppliers rather than values, so INFO always
        // reports the current state instead of a snapshot taken at startup.
        stats.describeMode(config.mode() == ServerConfig.ServerMode.VIRTUAL_THREADS
                ? "virtual-threads" : "event-loop");
        stats.trackExpiry(expiry::keysReaped, expiry::cyclesRun);
        if (aof != null) {
            AofWriter writer = aof;
            stats.describePersistence(
                    config.fsyncPolicy().name().toLowerCase(java.util.Locale.ROOT));
            stats.trackAof(writer::commandsAppended, writer::syncs);
        }

        CommandExecutor executor = new CommandExecutor(registry, database, stats, aof);
        RedisServer server = ServerFactory.create(config, executor, stats);

        try {
            server.start();
        } catch (IOException e) {
            System.err.println("resp-server: could not bind "
                    + config.bindAddress() + ":" + config.port() + " -- " + e.getMessage());
            System.exit(70);
            return;
        }

        printBanner(config, server, registry, restored);

        // Hold the main thread until the JVM is asked to exit. The latch is never counted
        // down here; the shutdown hook ends the process.
        CountDownLatch shutdown = new CountDownLatch(1);
        AofWriter aofToClose = aof;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println();
            System.out.println("[resp-server] shutting down");
            server.close();
            expiry.close();
            if (aofToClose != null) {
                // Flush and fsync on the way out, so a clean shutdown never loses a write
                // regardless of the configured policy.
                aofToClose.close();
            }
            shutdown.countDown();
        }, "resp-shutdown"));

        try {
            shutdown.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            server.close();
        }
    }

    private static void printBanner(ServerConfig config,
                                    RedisServer server,
                                    CommandRegistry registry,
                                    AofLoader.Result restored) {
        String mode = config.mode() == ServerConfig.ServerMode.VIRTUAL_THREADS
                ? "virtual threads (one per connection)"
                : "event loop (single-threaded NIO)";

        System.out.println("resp-server 0.3.0");
        System.out.println("  listening   " + config.bindAddress() + ":" + server.port());
        System.out.println("  mode        " + mode);
        System.out.println("  commands    " + registry.size());
        System.out.println("  java        " + System.getProperty("java.version"));
        if (config.persistenceEnabled()) {
            System.out.println("  aof         " + config.aofPath()
                    + " (fsync " + config.fsyncPolicy().name().toLowerCase(java.util.Locale.ROOT) + ")");
        } else {
            System.out.println("  aof         disabled");
        }
        if (restored != null && restored.commandsReplayed() > 0) {
            System.out.println("  restored    " + restored.commandsReplayed()
                    + " commands, " + restored.keysLoaded() + " keys"
                    + (restored.truncated() ? " (discarded an incomplete trailing record)" : ""));
        }
        System.out.println();
        System.out.println("  connect with:  redis-cli -p " + server.port());
        System.out.println();
    }
}
