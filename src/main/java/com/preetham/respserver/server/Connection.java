package com.preetham.respserver.server;

import com.preetham.respserver.command.ClientSession;
import com.preetham.respserver.command.CommandExecutor;
import com.preetham.respserver.protocol.ProtocolException;
import com.preetham.respserver.protocol.ReadBuffer;
import com.preetham.respserver.protocol.RespReader;
import com.preetham.respserver.protocol.RespValue;
import com.preetham.respserver.protocol.RespWriter;
import com.preetham.respserver.stats.ServerStats;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Handles one client connection with blocking I/O, running on its own virtual thread.
 *
 * <h2>The read loop, and why it has two nested loops</h2>
 *
 * The outer loop reads whatever the kernel has. The inner loop drains <em>every</em>
 * complete command out of the buffer before reading again. Those are different
 * quantities: one read may deliver half a command, or thirty-seven of them.
 *
 * <p>The inner loop is also, for free, the entire implementation of pipelining. A
 * client that writes 100 commands in one go has them parsed and answered in one pass,
 * with a single {@code flush} at the end -- so 100 commands cost one write syscall
 * instead of 100. That batching is most of why pipelining is fast, and it falls out of
 * the structure rather than needing a special case.
 *
 * <h2>Which failures close the connection</h2>
 *
 * A command failing is normal: bad arity or a wrong type produces an error reply and
 * the connection continues. A <em>frame</em> failing is not recoverable -- once the
 * byte stream is desynchronised there is no way to find the next command boundary, so
 * the only correct action is to report the error and close. That distinction is the
 * reason {@link ProtocolException} is separate from
 * {@link com.preetham.respserver.command.CommandException}.
 *
 * <h2>Why blocking I/O is fine here</h2>
 *
 * On a platform thread, parking one thread per connection would cost a megabyte or two
 * of stack each and make 10,000 connections impractical. On a virtual thread the park
 * costs a few hundred bytes and the carrier thread is released, so the straightforward
 * blocking code below scales to numbers that used to require an event loop. Proving or
 * disproving that against {@link EventLoopServer} is the point of the benchmark.
 */
final class Connection implements Runnable {

    private static final int IO_BUFFER_SIZE = 16 * 1024;

    private final Socket socket;
    private final CommandExecutor executor;
    private final ServerStats stats;
    private final boolean verbose;

    private final ClientSession session = new ClientSession();
    private final RespReader reader = RespReader.forServer();
    private final ReadBuffer inbound = new ReadBuffer();

    Connection(Socket socket,
               CommandExecutor executor,
               ServerStats stats,
               boolean verbose) {
        this.socket = socket;
        this.executor = executor;
        this.stats = stats;
        this.verbose = verbose;
    }

    @Override
    public void run() {
        stats.connectionOpened();
        if (verbose) {
            System.out.println("[resp-server] client " + session.id()
                    + " connected from " + socket.getRemoteSocketAddress());
        }

        try (Socket managed = socket;
             InputStream in = new BufferedInputStream(managed.getInputStream(), IO_BUFFER_SIZE);
             OutputStream out = new BufferedOutputStream(managed.getOutputStream(), IO_BUFFER_SIZE)) {

            // Nagle's algorithm delays small writes hoping to coalesce them. For a
            // request/response protocol that just adds latency to every reply.
            managed.setTcpNoDelay(true);

            byte[] chunk = new byte[IO_BUFFER_SIZE];

            while (!session.isCloseRequested()) {
                int read = in.read(chunk);
                if (read < 0) {
                    break; // peer closed cleanly
                }
                inbound.append(chunk, 0, read);

                if (drainCommands(out)) {
                    out.flush();
                }
            }
        } catch (ProtocolException e) {
            stats.protocolError();
            reportProtocolErrorAndClose(e);
        } catch (IOException e) {
            // A client vanishing mid-connection is routine, not an error worth logging.
            if (verbose) {
                System.out.println("[resp-server] client " + session.id() + ": " + e.getMessage());
            }
        } finally {
            stats.connectionClosed();
            if (verbose) {
                System.out.println("[resp-server] client " + session.id() + " disconnected");
            }
        }
    }

    /**
     * Parses and answers every complete command sitting in the buffer.
     *
     * @return true if anything was written, so the caller knows whether to flush
     */
    private boolean drainCommands(OutputStream out) throws IOException, ProtocolException {
        ByteBuffer parsing = inbound.forParsing();
        boolean wrote = false;

        while (true) {
            Optional<RespValue> frame = reader.tryParse(parsing);
            if (frame.isEmpty()) {
                break; // partial command; wait for more bytes
            }
            Optional<RespValue> reply = execute(frame.get());
            if (reply.isPresent()) {
                RespWriter.write(reply.get(), out);
                wrote = true;
            }
            if (session.isCloseRequested()) {
                break;
            }
        }

        // Discard exactly what the parser consumed, keeping any partial tail.
        inbound.consume(parsing.position());
        return wrote;
    }

    /**
     * Turns one parsed frame into a reply.
     *
     * @return the reply, or empty when no reply is due (an empty inline line)
     */
    private Optional<RespValue> execute(RespValue frame) {
        if (!(frame instanceof RespValue.ArrayReply(List<RespValue> items))) {
            return Optional.of(RespValue.error(
                    "ERR Protocol error: expected '*', got a bare value"));
        }
        if (items.isEmpty()) {
            // Redis silently ignores an empty command, e.g. a blank line from telnet.
            return Optional.empty();
        }

        List<RespValue.BulkString> args = new ArrayList<>(items.size());
        for (RespValue item : items) {
            if (!(item instanceof RespValue.BulkString bulk)) {
                return Optional.of(RespValue.error(
                        "ERR Protocol error: expected a bulk string as an argument"));
            }
            args.add(bulk);
        }

        return Optional.of(executor.execute(args, session));
    }

    /**
     * Best-effort: tell the client why before hanging up. The socket may already be
     * gone, in which case there is nothing useful left to do.
     */
    private void reportProtocolErrorAndClose(ProtocolException e) {
        try {
            OutputStream out = socket.getOutputStream();
            RespWriter.write(RespValue.error("ERR Protocol error: " + e.getMessage()), out);
            out.flush();
        } catch (IOException ignored) {
            // The connection is being torn down anyway.
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Nothing left to do.
            }
        }
    }
}
