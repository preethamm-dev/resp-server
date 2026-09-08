package com.preetham.respserver;

import com.preetham.respserver.command.CommandRegistry;
import com.preetham.respserver.config.ServerConfig;
import com.preetham.respserver.server.RedisServer;
import com.preetham.respserver.server.ServerFactory;
import com.preetham.respserver.stats.ServerStats;
import com.preetham.respserver.store.Database;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;

/** Entry point: parses configuration, starts a server, and waits for shutdown. */
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
        RedisServer server = ServerFactory.create(config, database, registry, stats);

        try {
            server.start();
        } catch (IOException e) {
            System.err.println("resp-server: could not bind "
                    + config.bindAddress() + ":" + config.port() + " -- " + e.getMessage());
            System.exit(70); // EX_SOFTWARE
            return;
        }

        printBanner(config, server, registry);

        // Hold the main thread until the JVM is asked to exit. The latch is never
        // counted down; the shutdown hook is what actually ends the process.
        CountDownLatch shutdown = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println();
            System.out.println("[resp-server] shutting down");
            server.close();
            shutdown.countDown();
        }, "resp-shutdown"));

        try {
            shutdown.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            server.close();
        }
    }

    private static void printBanner(ServerConfig config, RedisServer server, CommandRegistry registry) {
        String mode = config.mode() == ServerConfig.ServerMode.VIRTUAL_THREADS
                ? "virtual threads (one per connection)"
                : "event loop (single-threaded NIO)";

        System.out.println("resp-server 0.1.0");
        System.out.println("  listening   " + config.bindAddress() + ":" + server.port());
        System.out.println("  mode        " + mode);
        System.out.println("  commands    " + registry.size());
        System.out.println("  java        " + System.getProperty("java.version"));
        System.out.println();
        System.out.println("  connect with:  redis-cli -p " + server.port());
        System.out.println();
    }
}
