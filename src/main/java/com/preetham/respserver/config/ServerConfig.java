package com.preetham.respserver.config;

import com.preetham.respserver.persistence.FsyncPolicy;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Runtime configuration, parsed from command-line arguments.
 *
 * <p>{@link ServerMode} is the important one: it selects between the two concurrency
 * models this project exists to compare. Both are wired to the same command layer and
 * the same keyspace, so switching modes changes only how bytes get on and off the
 * socket -- which is what makes a benchmark between them meaningful rather than a
 * comparison of two different servers.
 *
 * @param aofPath path to the append-only file, or null when persistence is off
 */
public record ServerConfig(
        String bindAddress,
        int port,
        ServerMode mode,
        int maxClients,
        Path aofPath,
        FsyncPolicy fsyncPolicy,
        long expiryIntervalMillis,
        boolean verbose) {

    public static final int DEFAULT_PORT = 6380;
    public static final long DEFAULT_EXPIRY_INTERVAL_MILLIS = 100;

    public enum ServerMode {
        /** One virtual thread per connection, blocking I/O. */
        VIRTUAL_THREADS,
        /** A single thread with an NIO selector, non-blocking I/O. */
        EVENT_LOOP;

        static ServerMode parse(String raw) {
            return switch (raw.toLowerCase(Locale.ROOT)) {
                case "virtual", "virtual-threads", "vt" -> VIRTUAL_THREADS;
                case "eventloop", "event-loop", "nio", "el" -> EVENT_LOOP;
                default -> throw new IllegalArgumentException(
                        "unknown mode '" + raw + "' (expected 'virtual' or 'eventloop')");
            };
        }
    }

    public boolean persistenceEnabled() {
        return aofPath != null;
    }

    public static ServerConfig defaults() {
        return new ServerConfig("127.0.0.1", DEFAULT_PORT, ServerMode.VIRTUAL_THREADS, 10_000,
                null, FsyncPolicy.EVERYSEC, DEFAULT_EXPIRY_INTERVAL_MILLIS, false);
    }

    /** Configuration for tests: an ephemeral port, no persistence. */
    public static ServerConfig forTests(ServerMode mode) {
        return new ServerConfig("127.0.0.1", 0, mode, 1000,
                null, FsyncPolicy.NO, DEFAULT_EXPIRY_INTERVAL_MILLIS, false);
    }

    public static ServerConfig parse(String[] args) {
        String bind = "127.0.0.1";
        int port = DEFAULT_PORT;
        ServerMode mode = ServerMode.VIRTUAL_THREADS;
        int maxClients = 10_000;
        Path aofPath = null;
        FsyncPolicy fsync = FsyncPolicy.EVERYSEC;
        long expiryInterval = DEFAULT_EXPIRY_INTERVAL_MILLIS;
        boolean verbose = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port", "-p" -> port = Integer.parseInt(requireValue(args, ++i, "--port"));
                case "--bind", "-b" -> bind = requireValue(args, ++i, "--bind");
                case "--mode", "-m" -> mode = ServerMode.parse(requireValue(args, ++i, "--mode"));
                case "--max-clients" ->
                        maxClients = Integer.parseInt(requireValue(args, ++i, "--max-clients"));
                case "--appendonly" -> aofPath = Path.of(requireValue(args, ++i, "--appendonly"));
                case "--fsync" -> fsync = FsyncPolicy.parse(requireValue(args, ++i, "--fsync"));
                case "--expiry-interval" -> expiryInterval =
                        Long.parseLong(requireValue(args, ++i, "--expiry-interval"));
                case "--verbose", "-v" -> verbose = true;
                case "--help", "-h" -> {
                    printUsage();
                    System.exit(0);
                }
                default -> throw new IllegalArgumentException("unknown option: " + args[i]);
            }
        }

        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        if (expiryInterval < 1) {
            throw new IllegalArgumentException("expiry interval must be at least 1ms");
        }
        return new ServerConfig(bind, port, mode, maxClients, aofPath, fsync, expiryInterval, verbose);
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " requires a value");
        }
        return args[index];
    }

    public static void printUsage() {
        System.out.println("""
                resp-server -- a Redis-compatible server in Java 21

                Usage: java -jar resp-server.jar [options]

                Options:
                  -p, --port <n>            port to listen on (default 6380)
                  -b, --bind <addr>         address to bind (default 127.0.0.1)
                  -m, --mode <mode>         concurrency model:
                                              virtual   one virtual thread per connection (default)
                                              eventloop single-threaded NIO selector
                      --max-clients <n>     maximum concurrent connections (default 10000)
                      --appendonly <path>   enable AOF persistence at this path
                      --fsync <policy>      always | everysec (default) | no
                      --expiry-interval <n> active expiry cycle interval in ms (default 100)
                  -v, --verbose             log each connection
                  -h, --help                show this message

                Connect with the official client:
                  redis-cli -p 6380
                """);
    }
}
