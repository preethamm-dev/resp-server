package com.preetham.respserver.persistence;

import java.util.Locale;

/**
 * How aggressively the append-only file is forced to disk.
 *
 * <h2>Why a write is not a save</h2>
 *
 * {@code write()} only hands bytes to the operating system's page cache. They sit in RAM
 * until the kernel decides to flush them, which can be tens of seconds later. If the
 * process crashes the data survives, because the kernel still holds it -- but if the
 * <em>machine</em> loses power, it is gone. Only {@code fsync()} forces the data onto the
 * physical device, and it is expensive: a real disk flush can take milliseconds, which is
 * thousands of times longer than the command that produced it.
 *
 * <p>There is no correct answer, only a position on a curve, which is why this is a
 * setting rather than a decision baked into the code.
 */
public enum FsyncPolicy {

    /**
     * fsync after every write command. Nothing acknowledged is ever lost, even to a power
     * cut -- and throughput collapses to roughly the disk's flush rate.
     */
    ALWAYS,

    /**
     * fsync once per second in the background. At most one second of acknowledged writes
     * is lost to a power failure; a process crash loses nothing, since the bytes are
     * already in the kernel. This is Redis's default and the sensible one for almost
     * everybody.
     */
    EVERYSEC,

    /**
     * Never fsync explicitly; let the kernel choose. Fastest, and the only mode where a
     * power failure can lose a large, unbounded window of writes.
     */
    NO;

    public static FsyncPolicy parse(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "always" -> ALWAYS;
            case "everysec", "every-sec", "everysecond" -> EVERYSEC;
            case "no", "none", "off" -> NO;
            default -> throw new IllegalArgumentException(
                    "unknown fsync policy '" + raw + "' (expected always, everysec or no)");
        };
    }
}
