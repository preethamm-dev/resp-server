package com.preetham.respserver.server;

import com.preetham.respserver.command.CommandExecutor;
import com.preetham.respserver.config.ServerConfig;
import com.preetham.respserver.stats.ServerStats;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Thread-per-connection server on virtual threads.
 *
 * <p>This is the design that non-blocking I/O was invented to escape: accept a socket,
 * dedicate a thread to it, write plain blocking code. It fell out of favour because an
 * OS thread costs a megabyte or more of stack, so ten thousand connections meant ten
 * thousand threads and an unusable machine.
 *
 * <p>Virtual threads change the arithmetic. A parked virtual thread costs a few hundred
 * bytes on the heap and releases its carrier, so the JDK can keep hundreds of thousands
 * of them. The result is that the simplest possible code -- the read loop in
 * {@link Connection} -- may now scale as well as a hand-written selector loop. Whether
 * it actually does, on this machine and this workload, is what the benchmark measures.
 *
 * <p>The cost is not free: many threads touching one keyspace need real synchronisation,
 * which {@link EventLoopServer} avoids entirely by having one thread. That trade is the
 * heart of the comparison.
 *
 * <h2>Why the acceptor is a platform thread</h2>
 *
 * It spends its life blocked in {@code accept()} and never benefits from being virtual.
 * More importantly it must stay alive to keep the server running, and a virtual thread
 * is a daemon that would not.
 */
public final class VirtualThreadServer implements RedisServer {

    private final ServerConfig config;
    private final CommandExecutor executor;
    private final ServerStats stats;

    private volatile ServerSocket serverSocket;
    private volatile ExecutorService connectionExecutor;
    private volatile Thread acceptor;
    private volatile boolean running;

    public VirtualThreadServer(ServerConfig config,
                               CommandExecutor executor,
                               ServerStats stats) {
        this.config = config;
        this.executor = executor;
        this.stats = stats;
    }

    @Override
    public void start() throws IOException {
        ServerSocket socket = new ServerSocket();
        // Without SO_REUSEADDR a restart can fail while the previous socket sits in
        // TIME_WAIT -- which makes the test suite intermittently unable to rebind.
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(config.bindAddress(), config.port()), 512);

        this.serverSocket = socket;
        this.connectionExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.running = true;

        Thread thread = new Thread(this::acceptLoop, "resp-acceptor");
        thread.start();
        this.acceptor = thread;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();

                if (stats.connectedClients() >= config.maxClients()) {
                    // Refuse rather than accept and immediately struggle. Redis replies
                    // with an error before closing so the client knows why.
                    stats.connectionRejected();
                    rejectPolitely(client);
                    continue;
                }

                connectionExecutor.submit(
                        new Connection(client, executor, stats, config.verbose()));

            } catch (SocketException e) {
                // close() closes the listening socket, which lands here. Expected.
                if (running) {
                    System.err.println("[resp-server] accept failed: " + e.getMessage());
                }
                return;
            } catch (IOException e) {
                if (running) {
                    System.err.println("[resp-server] accept failed: " + e.getMessage());
                }
            }
        }
    }

    private void rejectPolitely(Socket client) {
        try (Socket rejected = client) {
            rejected.getOutputStream().write(
                    "-ERR max number of clients reached\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            rejected.getOutputStream().flush();
        } catch (IOException ignored) {
            // The client is being dropped regardless.
        }
    }

    @Override
    public int port() {
        ServerSocket socket = serverSocket;
        return socket == null ? config.port() : socket.getLocalPort();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void close() {
        if (!running) {
            return;
        }
        running = false;

        // Closing the listening socket unblocks accept() so the acceptor can exit.
        ServerSocket socket = serverSocket;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Shutting down; nothing useful to do.
            }
        }

        ExecutorService executor = connectionExecutor;
        if (executor != null) {
            // Interrupt in-flight connections: they are blocked in read() and will not
            // notice a shutdown request on their own.
            executor.shutdownNow();
            try {
                executor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        Thread thread = acceptor;
        if (thread != null) {
            try {
                thread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
