package com.preetham.respserver;

import static org.assertj.core.api.Assertions.assertThat;

import com.preetham.respserver.command.CommandExecutor;
import com.preetham.respserver.command.CommandRegistry;
import com.preetham.respserver.config.ServerConfig;
import com.preetham.respserver.server.RedisServer;
import com.preetham.respserver.server.ServerFactory;
import com.preetham.respserver.stats.ServerStats;
import com.preetham.respserver.store.Database;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;

/**
 * The failure modes that are specific to non-blocking I/O.
 *
 * <p>{@code EventLoopServerIT} already proves the event loop behaves identically to the
 * virtual-thread server for ordinary traffic. What it does not exercise is the machinery
 * that only engages under pressure: a reply too large for the kernel's send buffer, a
 * client that stops reading, hundreds of sockets at once, an abrupt disconnect. Those are
 * where partial writes, {@code OP_WRITE} registration and connection cleanup actually get
 * used, and where the classic NIO bugs live.
 */
class EventLoopStressIT {

    private RedisServer server;
    private Database database;
    private int port;

    @BeforeEach
    void startServer() throws IOException {
        ServerConfig config = ServerConfig.forTests(ServerConfig.ServerMode.EVENT_LOOP);
        database = new Database();
        CommandRegistry registry = CommandRegistry.standard();
        ServerStats stats = new ServerStats();
        CommandExecutor executor = new CommandExecutor(registry, database, stats, null);

        server = ServerFactory.create(config, executor, stats);
        server.start();
        port = server.port();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    @DisplayName("a reply far larger than the send buffer is delivered whole")
    void largeReplyIsDeliveredCompletely() {
        // 16 MB in one value: far beyond any kernel send buffer, so the server cannot
        // possibly write it in one call and must park the remainder and resume on
        // OP_WRITE. A broken partial-write path truncates or hangs here.
        String large = "x".repeat(16 * 1024 * 1024);

        try (Jedis jedis = new Jedis("127.0.0.1", port)) {
            jedis.set("big", large);
            String returned = jedis.get("big");

            assertThat(returned).hasSameSizeAs(large);
            assertThat(returned).isEqualTo(large);
        }
    }

    @Test
    @DisplayName("a slow reader forces the partial-write path and still receives everything")
    void slowReaderStillGetsTheWholeReply() throws Exception {
        int valueSize = 8 * 1024 * 1024;
        try (Jedis setup = new Jedis("127.0.0.1", port)) {
            setup.set("big", "y".repeat(valueSize));
        }

        // A raw socket with a deliberately small receive buffer, read slowly. The server's
        // send buffer fills almost immediately and stays full, so the reply can only be
        // delivered across many OP_WRITE cycles.
        try (Socket socket = new Socket()) {
            socket.setReceiveBufferSize(4096);
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", port));

            OutputStream out = socket.getOutputStream();
            out.write("*2\r\n$3\r\nGET\r\n$3\r\nbig\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();

            // Give the server time to fill the socket and be forced into OP_WRITE.
            Thread.sleep(300);

            InputStream in = socket.getInputStream();
            byte[] chunk = new byte[8192];
            long total = 0;
            int read;
            socket.setSoTimeout(30_000);
            // Header "$8388608\r\n" plus the payload plus the trailing CRLF.
            long expected = ("$" + valueSize + "\r\n").length() + valueSize + 2;
            while (total < expected && (read = in.read(chunk)) > 0) {
                total += read;
            }

            assertThat(total).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("the loop does not spin after a large write drains")
    void doesNotBusyLoopOnceTheWriteBufferEmpties() throws Exception {
        // The classic NIO bug: OP_WRITE registered for a partial write and never cleared.
        // A socket with room is writable essentially always, so select() then returns
        // immediately forever -- the server keeps working while burning a whole core, and
        // no functional test notices. Measuring the loop thread's CPU time does.
        try (Jedis jedis = new Jedis("127.0.0.1", port)) {
            jedis.set("big", "z".repeat(8 * 1024 * 1024));
            jedis.get("big");
            assertThat(jedis.ping()).isEqualTo("PONG");
        }

        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        long loopThreadId = findEventLoopThreadId();
        assertThat(loopThreadId).as("event loop thread should exist").isNotEqualTo(-1);

        long before = threads.getThreadCpuTime(loopThreadId);
        Thread.sleep(1000);
        long after = threads.getThreadCpuTime(loopThreadId);

        Duration burned = Duration.ofNanos(after - before);

        // Idle for a second, the loop should wake only on its 200ms select timeout, so a
        // few milliseconds of CPU at most. A spinning loop would consume close to 1000ms.
        assertThat(burned)
                .as("CPU burned while idle -- a spinning selector would be near 1000ms")
                .isLessThan(Duration.ofMillis(250));
    }

    private static long findEventLoopThreadId() {
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if ("resp-event-loop".equals(thread.getName())) {
                return thread.threadId();
            }
        }
        return -1;
    }

    @Test
    @DisplayName("200 concurrent clients are all served correctly")
    void handlesManyConcurrentClients() throws Exception {
        int clients = 200;
        int perClient = 50;
        CountDownLatch ready = new CountDownLatch(clients);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(clients);
        AtomicInteger failures = new AtomicInteger();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int c = 0; c < clients; c++) {
                int clientId = c;
                pool.submit(() -> {
                    try (Jedis jedis = new Jedis("127.0.0.1", port)) {
                        jedis.ping();
                        ready.countDown();
                        go.await();

                        for (int i = 0; i < perClient; i++) {
                            String key = "client:" + clientId + ":" + i;
                            jedis.set(key, String.valueOf(i));
                            if (!String.valueOf(i).equals(jedis.get(key))) {
                                failures.incrementAndGet();
                            }
                        }
                    } catch (Exception e) {
                        failures.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertThat(ready.await(30, TimeUnit.SECONDS)).as("all clients connected").isTrue();
            go.countDown(); // release them together, maximising concurrency
            assertThat(done.await(60, TimeUnit.SECONDS)).as("all clients finished").isTrue();
        }

        assertThat(failures.get()).isZero();
        assertThat(database.size()).isEqualTo(clients * perClient);
    }

    @Test
    @DisplayName("clients interleaved on one thread never receive each other's replies")
    void repliesGoToTheRightConnection() throws Exception {
        int clients = 20;
        List<Jedis> connections = new ArrayList<>();
        try {
            for (int i = 0; i < clients; i++) {
                Jedis jedis = new Jedis("127.0.0.1", port);
                jedis.set("owner", "client-" + i); // same key, different values
                connections.add(jedis);
            }

            // Round-robin so the loop is constantly switching between connections; a
            // buffer shared where it should be per-connection shows up here.
            for (int round = 0; round < 20; round++) {
                for (int i = 0; i < clients; i++) {
                    Jedis jedis = connections.get(i);
                    String unique = "client-" + i + "-round-" + round;
                    jedis.set("k:" + i, unique);
                    assertThat(jedis.get("k:" + i)).isEqualTo(unique);
                }
            }
        } finally {
            connections.forEach(Jedis::close);
        }
    }

    @Test
    @DisplayName("a client that vanishes mid-command does not disturb the others")
    void abruptDisconnectIsHandledCleanly() throws Exception {
        try (Jedis survivor = new Jedis("127.0.0.1", port)) {
            survivor.set("before", "ok");

            for (int i = 0; i < 50; i++) {
                Socket rude = new Socket("127.0.0.1", port);
                // Half a command, then hang up without waiting for anything.
                rude.getOutputStream().write("*3\r\n$3\r\nSET\r\n$1\r\n".getBytes(StandardCharsets.US_ASCII));
                rude.getOutputStream().flush();
                rude.setSoLinger(true, 0); // RST rather than a graceful FIN
                rude.close();
            }

            assertThat(survivor.get("before")).isEqualTo("ok");
            assertThat(survivor.ping()).isEqualTo("PONG");
        }
    }

    @Test
    @DisplayName("a client that connects and immediately closes is cleaned up")
    void immediateDisconnectsDoNotLeakConnections() throws Exception {
        for (int i = 0; i < 200; i++) {
            new Socket("127.0.0.1", port).close();
        }

        try (Jedis jedis = new Jedis("127.0.0.1", port)) {
            assertThat(jedis.ping()).isEqualTo("PONG");
        }
    }

    @Test
    @DisplayName("a 10000-command pipeline is answered in order on one connection")
    void largePipelineIsAnsweredInOrder() {
        try (Jedis jedis = new Jedis("127.0.0.1", port)) {
            var pipeline = jedis.pipelined();
            for (int i = 0; i < 10_000; i++) {
                pipeline.set("p:" + i, "v:" + i);
            }
            List<Object> results = pipeline.syncAndReturnAll();
            assertThat(results).hasSize(10_000);

            var readback = jedis.pipelined();
            List<redis.clients.jedis.Response<String>> responses = new ArrayList<>();
            for (int i = 0; i < 10_000; i++) {
                responses.add(readback.get("p:" + i));
            }
            readback.sync();

            for (int i = 0; i < 10_000; i++) {
                assertThat(responses.get(i).get())
                        .as("reply %d must correspond to request %d", i, i)
                        .isEqualTo("v:" + i);
            }
        }
    }

    @Test
    @DisplayName("a command split across many tiny TCP segments is reassembled")
    void handlesCommandArrivingOneByteAtATime() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setTcpNoDelay(true); // send each byte as its own segment
            socket.setSoTimeout(15_000);

            byte[] command = "*3\r\n$3\r\nSET\r\n$4\r\ndrip\r\n$5\r\nvalue\r\n"
                    .getBytes(StandardCharsets.US_ASCII);
            OutputStream out = socket.getOutputStream();
            for (byte b : command) {
                out.write(b);
                out.flush();
                Thread.sleep(1);
            }

            byte[] reply = new byte[5];
            int read = socket.getInputStream().read(reply);

            assertThat(new String(reply, 0, read, StandardCharsets.US_ASCII)).isEqualTo("+OK\r\n");
        }

        assertThat(database.getString("drip").orElseThrow().asString()).isEqualTo("value");
    }

    @Test
    @DisplayName("QUIT is answered before the socket closes")
    void quitFlushesItsReplyBeforeClosing() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write("QUIT\r\n".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            byte[] reply = new byte[5];
            int read = socket.getInputStream().read(reply);
            assertThat(new String(reply, 0, read, StandardCharsets.US_ASCII)).isEqualTo("+OK\r\n");

            // And then the server really does hang up.
            assertThat(socket.getInputStream().read()).isEqualTo(-1);
        }
    }

    @Test
    @DisplayName("a protocol error closes only the offending connection")
    void protocolErrorClosesOneConnectionNotTheServer() throws Exception {
        try (Jedis survivor = new Jedis("127.0.0.1", port)) {
            survivor.set("k", "v");

            try (Socket bad = new Socket("127.0.0.1", port)) {
                bad.setSoTimeout(5000);
                bad.getOutputStream().write("*3\r\n$-5\r\n".getBytes(StandardCharsets.US_ASCII));
                bad.getOutputStream().flush();

                byte[] reply = new byte[256];
                int read = bad.getInputStream().read(reply);
                assertThat(new String(reply, 0, read, StandardCharsets.US_ASCII))
                        .startsWith("-ERR Protocol error");
            }

            assertThat(survivor.get("k")).isEqualTo("v");
            assertThat(survivor.ping()).isEqualTo("PONG");
        }
    }
}
