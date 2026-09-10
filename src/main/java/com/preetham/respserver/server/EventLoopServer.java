package com.preetham.respserver.server;

import com.preetham.respserver.command.ClientSession;
import com.preetham.respserver.command.CommandExecutor;
import com.preetham.respserver.config.ServerConfig;
import com.preetham.respserver.protocol.ProtocolException;
import com.preetham.respserver.protocol.ReadBuffer;
import com.preetham.respserver.protocol.RespReader;
import com.preetham.respserver.protocol.RespValue;
import com.preetham.respserver.protocol.RespWriter;
import com.preetham.respserver.protocol.WriteBuffer;
import com.preetham.respserver.stats.ServerStats;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

/**
 * Single-threaded, non-blocking server built on an NIO {@link Selector}.
 *
 * <h2>The idea</h2>
 *
 * One thread handles every connection. Instead of asking a socket for data and waiting,
 * the loop asks the operating system <em>which sockets are ready right now</em>, services
 * exactly those without ever blocking, and goes round again. Underneath, the JDK uses
 * {@code epoll} on Linux, {@code kqueue} on BSD and IOCP on Windows.
 *
 * <p>This is how Redis, nginx and Node.js are built, and it is what non-blocking I/O was
 * invented for: before virtual threads, one OS thread per connection cost a megabyte or
 * more of stack, so ten thousand connections were impractical. An event loop makes the
 * cost per connection a socket and a few buffers.
 *
 * <h2>Readiness, not completion</h2>
 *
 * A selector reports that an operation <em>would not block</em> -- not that it finished.
 * So both directions need care:
 *
 * <ul>
 *   <li><b>Reads may be partial.</b> One {@code read()} can deliver half a command or two
 *       and a half. The incremental parser written on day one handles this unchanged: it
 *       consumes whole frames or nothing.</li>
 *   <li><b>Writes may be partial.</b> {@code write()} returns how many bytes the kernel
 *       took, which can be fewer than offered, or zero. The remainder is parked in a
 *       {@link WriteBuffer} and {@code OP_WRITE} is registered so the loop is told when the
 *       socket drains.</li>
 * </ul>
 *
 * <p>{@code OP_WRITE} must then be <em>deregistered</em> once the buffer empties. A socket
 * with room in its send buffer is writable essentially always, so leaving the interest set
 * would make {@code select()} return immediately forever -- a busy loop at 100% CPU that
 * still appears to work. It is the classic NIO bug, and the reason the interest set is
 * recomputed after every write.
 *
 * <h2>What the single thread buys</h2>
 *
 * No synchronisation. The keyspace, the buffers and the session state are touched by
 * exactly one thread, so the atomicity that {@link VirtualThreadServer} obtains through
 * {@code ConcurrentHashMap.compute} and per-collection locks is free here. That is
 * precisely why real Redis is single-threaded, and the trade this project measures.
 *
 * <p>The cost is that everything is serialised: one slow command delays every other
 * client (head-of-line blocking), and only one core is used no matter how many are
 * available.
 *
 * <h2>Buffer ownership</h2>
 *
 * The read buffer is shared by every connection, because there is only one thread and its
 * contents are copied out before the loop moves on. Write buffers are per connection,
 * because unsent data has to survive until the socket is ready again.
 */
public final class EventLoopServer implements RedisServer {

    private static final int READ_BUFFER_SIZE = 64 * 1024;

    private final ServerConfig config;
    private final CommandExecutor executor;
    private final ServerStats stats;

    /**
     * Shared by all connections: the loop is single-threaded and drains this into a
     * per-connection buffer before doing anything else, so it can be reused.
     */
    private final ByteBuffer readBuffer = ByteBuffer.allocateDirect(READ_BUFFER_SIZE);

    private volatile Selector selector;
    private volatile ServerSocketChannel serverChannel;
    private volatile Thread loopThread;
    private volatile boolean running;
    private final CountDownLatch started = new CountDownLatch(1);

    public EventLoopServer(ServerConfig config, CommandExecutor executor, ServerStats stats) {
        this.config = config;
        this.executor = executor;
        this.stats = stats;
    }

    /** Everything one connection needs, hung off its {@link SelectionKey} attachment. */
    private static final class Connection {
        final SocketChannel channel;
        final ClientSession session = new ClientSession();
        final RespReader reader = RespReader.forServer();
        final ReadBuffer inbound = new ReadBuffer();
        final WriteBuffer outbound = new WriteBuffer();
        boolean closeAfterFlush;

        Connection(SocketChannel channel) {
            this.channel = channel;
        }
    }

    @Override
    public void start() throws IOException {
        selector = Selector.open();
        serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        serverChannel.bind(new InetSocketAddress(config.bindAddress(), config.port()), 512);
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);

        running = true;
        Thread thread = new Thread(this::loop, "resp-event-loop");
        thread.start();
        loopThread = thread;

        // start() must not return before the loop is actually running, or a test that
        // connects immediately could race the first select().
        try {
            started.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void loop() {
        started.countDown();
        while (running) {
            try {
                // A timeout rather than an indefinite wait, so close() is noticed promptly
                // even if no socket becomes ready.
                selector.select(200);
            } catch (IOException e) {
                if (running) {
                    System.err.println("[resp-server] select failed: " + e.getMessage());
                }
                continue;
            } catch (ClosedSelectorException e) {
                return;
            }

            var iterator = selector.selectedKeys().iterator();
            while (iterator.hasNext()) {
                SelectionKey key = iterator.next();
                // Removing is mandatory: the selector does not clear the selected set, so
                // a key left behind is reprocessed on every pass forever.
                iterator.remove();

                if (!key.isValid()) {
                    continue;
                }
                try {
                    if (key.isAcceptable()) {
                        accept();
                        continue;
                    }
                    if (key.isReadable()) {
                        handleRead(key);
                    }
                    // handleRead may have closed the connection, so re-check validity
                    // before touching the key again.
                    if (key.isValid() && key.isWritable()) {
                        handleWrite(key);
                    }
                } catch (CancelledKeyException e) {
                    // Closed underneath us; nothing left to do.
                } catch (IOException e) {
                    closeConnection(key);
                }
            }
        }
    }

    private void accept() throws IOException {
        SocketChannel client = serverChannel.accept();
        if (client == null) {
            return;
        }

        if (stats.connectedClients() >= config.maxClients()) {
            stats.connectionRejected();
            rejectPolitely(client);
            return;
        }

        client.configureBlocking(false);
        // Nagle's algorithm delays small writes hoping to coalesce them, which adds
        // latency to every reply of a request/response protocol.
        client.setOption(StandardSocketOptions.TCP_NODELAY, true);
        client.register(selector, SelectionKey.OP_READ, new Connection(client));

        stats.connectionOpened();
        if (config.verbose()) {
            System.out.println("[resp-server] client connected from " + client.getRemoteAddress());
        }
    }

    private void rejectPolitely(SocketChannel client) {
        try (SocketChannel rejected = client) {
            rejected.write(ByteBuffer.wrap(
                    "-ERR max number of clients reached\r\n".getBytes(StandardCharsets.US_ASCII)));
        } catch (IOException ignored) {
            // The client is being dropped regardless.
        }
    }

    private void handleRead(SelectionKey key) throws IOException {
        Connection connection = (Connection) key.attachment();

        readBuffer.clear();
        int read = connection.channel.read(readBuffer);

        if (read < 0) {
            closeConnection(key);
            return;
        }
        if (read == 0) {
            return;
        }

        readBuffer.flip();
        try {
            connection.inbound.append(readBuffer);
            drainCommands(connection);
        } catch (ProtocolException e) {
            // A malformed frame desynchronises the stream: there is no way to find the
            // next command boundary, so report and hang up. A failed *command* is
            // different and leaves the connection open.
            stats.protocolError();
            queue(connection, RespValue.error("ERR Protocol error: " + e.getMessage()));
            connection.closeAfterFlush = true;
        }

        flush(key, connection);
    }

    /** Parses and answers every complete command currently buffered. */
    private void drainCommands(Connection connection) throws ProtocolException {
        ByteBuffer parsing = connection.inbound.forParsing();

        while (true) {
            Optional<RespValue> frame = connection.reader.tryParse(parsing);
            if (frame.isEmpty()) {
                break; // partial command; wait for more bytes
            }
            executeFrame(connection, frame.get());
            if (connection.session.isCloseRequested()) {
                connection.closeAfterFlush = true;
                break;
            }
        }
        connection.inbound.consume(parsing.position());
    }

    private void executeFrame(Connection connection, RespValue frame) {
        if (!(frame instanceof RespValue.ArrayReply(List<RespValue> items))) {
            queue(connection, RespValue.error("ERR Protocol error: expected '*', got a bare value"));
            return;
        }
        if (items.isEmpty()) {
            return; // an empty inline line draws no reply, as in Redis
        }

        List<RespValue.BulkString> args = new ArrayList<>(items.size());
        for (RespValue item : items) {
            if (!(item instanceof RespValue.BulkString bulk)) {
                queue(connection, RespValue.error(
                        "ERR Protocol error: expected a bulk string as an argument"));
                return;
            }
            args.add(bulk);
        }

        queue(connection, executor.execute(args, connection.session));
    }

    private void queue(Connection connection, RespValue reply) {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream(64);
        try {
            RespWriter.write(reply, encoded);
        } catch (IOException e) {
            throw new AssertionError("encoding to memory cannot fail", e);
        }
        connection.outbound.append(encoded.toByteArray());
    }

    /**
     * Writes as much as the kernel will take and adjusts the interest set accordingly.
     *
     * <p>This is where the two classic NIO mistakes live. Forgetting to register
     * {@code OP_WRITE} on a partial write means the remainder is never sent and the client
     * hangs. Forgetting to deregister it once drained means {@code select()} returns
     * immediately forever, burning a core while still appearing to work.
     */
    private void flush(SelectionKey key, Connection connection) throws IOException {
        if (!connection.outbound.isEmpty()) {
            ByteBuffer pending = connection.outbound.forWriting();
            int written = connection.channel.write(pending);
            connection.outbound.consume(written);
        }

        if (connection.outbound.isEmpty()) {
            if (connection.closeAfterFlush) {
                closeConnection(key);
                return;
            }
            connection.outbound.compactIfOversized();
            key.interestOps(SelectionKey.OP_READ);
            return;
        }

        // Still bytes left: the socket's send buffer is full.
        if (connection.outbound.isOverLimit()) {
            // The client is not draining what it asked for. Buffering without bound is
            // how one slow consumer takes down the whole server.
            System.err.println("[resp-server] dropping a client with "
                    + connection.outbound.pending() + " unsent bytes");
            closeConnection(key);
            return;
        }
        key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
    }

    private void handleWrite(SelectionKey key) throws IOException {
        flush(key, (Connection) key.attachment());
    }

    private void closeConnection(SelectionKey key) {
        Connection connection = (Connection) key.attachment();
        key.cancel();
        try {
            if (connection != null) {
                connection.channel.close();
            }
        } catch (IOException ignored) {
            // Already going away.
        }
        if (connection != null) {
            stats.connectionClosed();
            if (config.verbose()) {
                System.out.println("[resp-server] client " + connection.session.id() + " disconnected");
            }
        }
    }

    @Override
    public int port() {
        ServerSocketChannel channel = serverChannel;
        if (channel == null) {
            return config.port();
        }
        try {
            return ((InetSocketAddress) channel.getLocalAddress()).getPort();
        } catch (IOException e) {
            return config.port();
        }
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

        Selector current = selector;
        if (current != null) {
            // The loop may be parked inside select(); wake it so it can notice !running.
            current.wakeup();
        }

        Thread thread = loopThread;
        if (thread != null) {
            try {
                thread.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (current != null) {
            for (SelectionKey key : current.keys()) {
                try {
                    key.channel().close();
                } catch (IOException ignored) {
                    // Shutting down.
                }
            }
            try {
                current.close();
            } catch (IOException ignored) {
                // Shutting down.
            }
        }
        try {
            if (serverChannel != null) {
                serverChannel.close();
            }
        } catch (IOException ignored) {
            // Shutting down.
        }
    }
}
