package com.preetham.respserver.command;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-connection state that outlives a single command.
 *
 * <p>Deliberately small. Redis keeps a great deal more here (output buffer limits,
 * subscribed channels, MULTI queue, authentication state); this server implements
 * none of those, so the session holds only what the supported commands need.
 *
 * <p>{@code closeRequested} exists because {@code QUIT} cannot close the socket
 * itself -- the {@code +OK} has to be written first. The handler sets the flag and
 * the connection loop closes after flushing.
 */
public final class ClientSession {

    private static final AtomicLong IDS = new AtomicLong();

    private final long id = IDS.incrementAndGet();
    private volatile boolean closeRequested;
    private volatile String name = "";

    public long id() {
        return id;
    }

    public boolean isCloseRequested() {
        return closeRequested;
    }

    public void requestClose() {
        this.closeRequested = true;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name == null ? "" : name;
    }
}
