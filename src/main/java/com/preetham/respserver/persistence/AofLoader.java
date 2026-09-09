package com.preetham.respserver.persistence;

import com.preetham.respserver.command.ClientSession;
import com.preetham.respserver.command.CommandContext;
import com.preetham.respserver.command.CommandRegistry;
import com.preetham.respserver.protocol.ProtocolException;
import com.preetham.respserver.protocol.RespReader;
import com.preetham.respserver.protocol.RespValue;
import com.preetham.respserver.stats.ServerStats;
import com.preetham.respserver.store.Database;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Rebuilds the keyspace at startup by replaying the append-only file.
 *
 * <h2>The truncated final record</h2>
 *
 * The interesting case is not the happy path, it is a crash. If the process died
 * mid-append -- or the machine lost power with a partial record in the page cache -- the
 * file ends with an incomplete RESP frame. There is no way to guess the missing bytes, so
 * the only safe action is to replay every complete command and discard the fragment.
 *
 * <p>The parser makes this straightforward for exactly the reason it was written that way
 * on day one: it already distinguishes "not enough bytes yet" from "malformed". At the end
 * of a file, "not enough bytes yet" simply means "this record was never finished".
 *
 * <p>The truncated tail is then removed from the file, so the next append starts from a
 * clean boundary. Leaving it would corrupt every subsequent record, because the fragment
 * would be parsed as the beginning of whatever was written next.
 *
 * <p>A discarded record is a genuinely lost write, but only one that was never
 * acknowledged as durable -- exactly the window the fsync policy defines.
 */
public final class AofLoader {

    /** Outcome of a load, so startup can report what happened. */
    public record Result(int commandsReplayed, int keysLoaded, long bytesRead, boolean truncated) {
    }

    private AofLoader() {
    }

    /**
     * Replays {@code path} into {@code database}. A missing file is not an error -- it is
     * simply the first start.
     */
    public static Result load(Path path, Database database, CommandRegistry registry)
            throws IOException {
        if (!Files.exists(path) || Files.size(path) == 0) {
            return new Result(0, 0, 0, false);
        }

        byte[] contents = Files.readAllBytes(path);
        ByteBuffer buffer = ByteBuffer.wrap(contents);

        RespReader reader = RespReader.strict();
        ClientSession session = new ClientSession();
        ServerStats stats = new ServerStats();

        int replayed = 0;
        int lastCompleteOffset = 0;
        boolean truncated = false;

        while (buffer.hasRemaining()) {
            Optional<RespValue> frame;
            try {
                frame = reader.tryParse(buffer);
            } catch (ProtocolException e) {
                // Corrupt rather than merely short. Everything before this point is still
                // sound, so keep it and stop.
                System.err.println("[resp-server] AOF is corrupt at byte " + lastCompleteOffset
                        + " (" + e.getMessage() + "); replaying up to that point");
                truncated = true;
                break;
            }

            if (frame.isEmpty()) {
                // Incomplete trailing record: the crash landed here.
                truncated = true;
                break;
            }

            if (frame.get() instanceof RespValue.ArrayReply(List<RespValue> items)
                    && !items.isEmpty()) {
                replay(items, database, registry, session, stats);
                replayed++;
            }
            lastCompleteOffset = buffer.position();
        }

        if (truncated) {
            truncateTo(path, lastCompleteOffset);
        }

        return new Result(replayed, database.size(), lastCompleteOffset, truncated);
    }

    private static void replay(List<RespValue> items,
                               Database database,
                               CommandRegistry registry,
                               ClientSession session,
                               ServerStats stats) {
        List<RespValue.BulkString> args = new ArrayList<>(items.size());
        for (RespValue item : items) {
            if (!(item instanceof RespValue.BulkString bulk)) {
                return; // not a command; skip it rather than abandoning the whole file
            }
            args.add(bulk);
        }

        // Dispatched through the ordinary command path, so replay cannot drift from live
        // behaviour. A second implementation of "apply a command" would be one more thing
        // to keep in step, and the bugs would only surface after a crash.
        CommandContext ctx = new CommandContext(args, database, session, stats, registry);
        RespValue reply = registry.dispatch(ctx);

        if (reply instanceof RespValue.ErrorReply(String message)) {
            System.err.println("[resp-server] AOF replay: command "
                    + args.get(0).asString() + " returned " + message);
        }
    }

    /** Drops a partial trailing record so the next append starts on a clean boundary. */
    private static void truncateTo(Path path, long length) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            long before = channel.size();
            if (before > length) {
                channel.truncate(length);
                System.out.println("[resp-server] discarded " + (before - length)
                        + " bytes of an incomplete trailing AOF record");
            }
        }
    }
}
