package dev.localsoul.aero.actor;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Aufzeichnung eines {@link dev.localsoul.aero.actor.session.Client} fuer Tests. */
public final class TestClient implements dev.localsoul.aero.actor.session.Client {

    private final long id;
    private final List<Object> packets = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean open = true;
    private volatile boolean closed;
    private volatile int failureAfter = -1;

    public TestClient(long id) {
        this.id = id;
    }

    public static TestClient of(long id) {
        return new TestClient(id);
    }

    /** Schliesst nach {@code n} Paketen — simuliert einen sterbenden Client. */
    public TestClient failingAfter(int n) {
        this.failureAfter = n;
        return this;
    }

    @Override
    public long id() {
        return id;
    }

    @Override
    public void send(Object packet) {
        if (!open) {
            throw new IllegalStateException("client " + id + " ist zu");
        }
        if (failureAfter >= 0 && packets.size() >= failureAfter) {
            throw new IllegalStateException("client " + id + " laeuft ueber");
        }
        packets.add(packet);
    }

    @Override
    public void close() {
        closed = true;
        open = false;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    public boolean wasClosed() {
        return closed;
    }

    public int sendCount() {
        return packets.size();
    }

    public List<Object> packets() {
        synchronized (packets) {
            return List.copyOf(packets);
        }
    }

    public Object lastPacket() {
        synchronized (packets) {
            return packets.isEmpty() ? null : packets.get(packets.size() - 1);
        }
    }

    @Override
    public String toString() {
        return "TestClient(" + id + ", " + sendCount() + " Pakete)";
    }
}
