package com.preetham.respserver.persistence;

import com.preetham.respserver.protocol.RespValue;
import com.preetham.respserver.protocol.RespWriter;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Appends every dataset-changing command to a file, so the keyspace can be rebuilt after
 * a restart.
 *
 * <h2>Why the log stores commands, not data</h2>
 *
 * The file is a sequence of RESP arrays -- literally the commands as the client sent
 * them. Replaying them in order reproduces the dataset exactly.
 *
 * <p>The appeal is that it needs no serialisation format of its own: the protocol already
 * is one, the file is human-readable, and {@code redis-cli --pipe < appendonly.aof} would
 * replay it into a real Redis. The cost is that the file grows with the number of
 * <em>writes</em> rather than the size of the data. A counter incremented a million times
 * is one key but a million records, which is why Redis periodically rewrites the log as
 * the shortest command sequence that recreates the current state. That rewrite is not
 * implemented here, and the README says so.
 *
 * <h2>Ordering</h2>
 *
 * Appends are serialised on one lock. That is not merely for safety of the file handle:
 * the log has to record the same order the keyspace applied, or replay would produce a
 * different dataset. Two clients incrementing the same counter must appear in the file in
 * the order the store accepted them.
 *
 * <p>This lock is a genuine bottleneck under the virtual-thread model -- every writing
 * client funnels through it. Single-threaded Redis gets that ordering for free. It is one
 * of the more concrete costs of the many-threads design.
 */
public final class AofWriter implements AutoCloseable {

    private final Path path;
    private final FsyncPolicy policy;
    private final FileOutputStream file;
    private final OutputStream buffered;
    private final Object lock = new Object();

    private final LongAdder commandsAppended = new LongAdder();
    private final LongAdder syncs = new LongAdder();

    private ScheduledExecutorService syncScheduler;
    private volatile boolean unsyncedWrites;
    private volatile boolean closed;

    public AofWriter(Path path, FsyncPolicy policy) throws IOException {
        this.path = path;
        this.policy = policy;

        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        this.file = new FileOutputStream(path.toFile(), true);
        this.buffered = new BufferedOutputStream(file, 64 * 1024);

        if (policy == FsyncPolicy.EVERYSEC) {
            startBackgroundSync();
        }
    }

    private void startBackgroundSync() {
        syncScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "resp-aof-fsync");
            thread.setDaemon(true);
            return thread;
        });
        syncScheduler.scheduleWithFixedDelay(() -> {
            try {
                if (unsyncedWrites) {
                    synchronized (lock) {
                        buffered.flush();
                        file.getFD().sync();
                        unsyncedWrites = false;
                    }
                    syncs.increment();
                }
            } catch (IOException e) {
                System.err.println("[resp-server] background fsync failed: " + e.getMessage());
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    /**
     * Records one command, encoded as the RESP array a client would have sent.
     *
     * <p>The buffer is flushed on every call regardless of policy, so the bytes always
     * reach the kernel. That is what makes even {@link FsyncPolicy#NO} survive a process
     * crash -- only a machine-level failure can lose them. The policy governs the far more
     * expensive step of forcing the kernel's cache onto the device.
     */
    public void append(List<RespValue.BulkString> args) {
        if (closed) {
            return;
        }
        List<RespValue> items = new ArrayList<>(args.size());
        items.addAll(args);
        byte[] encoded = RespWriter.encode(RespValue.array(items));

        try {
            synchronized (lock) {
                buffered.write(encoded);
                buffered.flush();

                if (policy == FsyncPolicy.ALWAYS) {
                    file.getFD().sync();
                    syncs.increment();
                } else {
                    unsyncedWrites = true;
                }
            }
            commandsAppended.increment();
        } catch (IOException e) {
            // Losing durability silently would be worse than noisy logging: the operator
            // needs to know the log is no longer trustworthy. The command itself already
            // succeeded in memory, so failing the client's request now would be a lie in
            // the other direction.
            System.err.println("[resp-server] failed to append to the AOF: " + e.getMessage());
        }
    }

    /** Forces everything written so far onto the device. */
    public void sync() throws IOException {
        synchronized (lock) {
            buffered.flush();
            file.getFD().sync();
            unsyncedWrites = false;
        }
        syncs.increment();
    }

    public Path path() {
        return path;
    }

    public FsyncPolicy policy() {
        return policy;
    }

    public long commandsAppended() {
        return commandsAppended.sum();
    }

    public long syncs() {
        return syncs.sum();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (syncScheduler != null) {
            syncScheduler.shutdownNow();
        }
        try {
            synchronized (lock) {
                buffered.flush();
                file.getFD().sync();
                buffered.close();
            }
        } catch (IOException e) {
            System.err.println("[resp-server] failed to close the AOF cleanly: " + e.getMessage());
        }
    }
}
