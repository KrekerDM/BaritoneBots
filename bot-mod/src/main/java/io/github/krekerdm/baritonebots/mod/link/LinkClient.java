package io.github.krekerdm.baritonebots.mod.link;

import io.github.krekerdm.baritonebots.common.Protocol;
import io.github.krekerdm.baritonebots.common.link.HostPort;
import io.github.krekerdm.baritonebots.common.link.LineCodec;
import io.github.krekerdm.baritonebots.common.msg.Envelope;
import io.github.krekerdm.baritonebots.common.msg.Hello;
import io.github.krekerdm.baritonebots.common.msg.MessageTypes;
import io.github.krekerdm.baritonebots.common.msg.Reject;
import io.github.krekerdm.baritonebots.mod.ModInfo;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * TCP JSON-lines client to the manager (SPEC §2, §4.1).
 * <p>
 * One connection thread connects, sends {@code hello}, waits for {@code welcome}/{@code reject} and then reads
 * lines into {@link #poll()}'s queue; a writer thread per connection drains the outbox. {@link #send} never blocks
 * and may be called from any thread. Inbound envelopes are only consumed on the client thread.
 */
public final class LinkClient {
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int HANDSHAKE_TIMEOUT_MS = 15_000;
    private static final int OUTBOX_CAPACITY = 4_096;

    private final HostPort address;
    private final Supplier<Hello> hello;
    private final ConcurrentLinkedQueue<Envelope> inbound = new ConcurrentLinkedQueue<>();
    private final LinkedBlockingDeque<String> outbox = new LinkedBlockingDeque<>(OUTBOX_CAPACITY);

    private volatile boolean running;
    private volatile boolean linked;
    private volatile Socket socket;
    private volatile String lastReject;
    private Thread thread;
    private String lastError;
    private long dropped;

    public LinkClient(HostPort address, Supplier<Hello> hello) {
        this.address = Objects.requireNonNull(address);
        this.hello = Objects.requireNonNull(hello);
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(this::run, "BaritoneBots-link");
        thread.setDaemon(true);
        thread.start();
    }

    /** True between {@code welcome} and the loss of the connection. */
    public boolean isLinked() {
        return linked;
    }

    /** Reason of the last {@code reject}, or {@code null}. */
    public String lastReject() {
        return lastReject;
    }

    /** Next inbound envelope or {@code null}; call on the client thread only. */
    public Envelope poll() {
        return inbound.poll();
    }

    /** Queues a message that is dropped while the link is down (status, log). */
    public void send(Envelope e) {
        send(e, false);
    }

    /**
     * Queues a message. {@code keepWhileDown} messages (task results, events, query results, container
     * snapshots) wait in the outbox until the link is back; when the outbox is full the oldest line is dropped.
     */
    public void send(Envelope e, boolean keepWhileDown) {
        if (!running || (!linked && !keepWhileDown)) {
            return;
        }
        String line;
        try {
            line = e.encode();
        } catch (RuntimeException ex) {
            ModInfo.LOG.error("Cannot encode '{}' message: {}", e.t(), ex.toString());
            return;
        }
        while (!outbox.offerLast(line)) {
            if (outbox.pollFirst() != null) {
                synchronized (this) {
                    dropped++;
                    if (dropped == 1 || dropped % 1000 == 0) {
                        ModInfo.LOG.warn("Link outbox full, dropped {} old message(s)", dropped);
                    }
                }
            }
        }
    }

    /** Stops reconnecting, gives the writer up to {@code flushMillis} to drain the outbox and closes the socket. */
    public void close(long flushMillis) {
        long deadline = System.currentTimeMillis() + Math.max(0, flushMillis);
        while (linked && !outbox.isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        running = false;
        closeSocket(socket);
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
    }

    private void run() {
        long backoff = Protocol.LINK_RECONNECT_MIN_MS;
        while (running) {
            Thread writer = null;
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress(address.host(), address.port()), CONNECT_TIMEOUT_MS);
                s.setTcpNoDelay(true);
                s.setKeepAlive(true);
                socket = s;
                OutputStream out = new BufferedOutputStream(s.getOutputStream());
                LineCodec in = new LineCodec(s.getInputStream(), Protocol.MAX_LINE_BYTES);
                LineCodec.writeLine(out, Envelope.of(MessageTypes.HELLO, hello.get()).encode(), Protocol.MAX_LINE_BYTES);

                s.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
                String first = in.readLine();
                if (first == null) {
                    throw new IOException("manager closed the link during the handshake");
                }
                Envelope answer = Envelope.decode(first);
                if (answer.is(MessageTypes.REJECT)) {
                    String reason = answer.payload(Reject.class).reason();
                    if (!Objects.equals(reason, lastReject)) {
                        ModInfo.LOG.error("Manager rejected this bot: {}", reason);
                    }
                    lastReject = reason;
                    // A wrong secret or unknown id only changes when the manager's config changes: retry slowly.
                    backoff = Protocol.LINK_RECONNECT_MAX_MS;
                } else if (!answer.is(MessageTypes.WELCOME)) {
                    throw new IOException("expected welcome, got '" + answer.t() + "'");
                } else {
                    s.setSoTimeout(0);
                    lastReject = null;
                    lastError = null;
                    inbound.add(answer);
                    linked = true;
                    backoff = Protocol.LINK_RECONNECT_MIN_MS;
                    ModInfo.LOG.info("Linked to manager at {}", address);
                    writer = startWriter(s, out);

                    String line;
                    while (running && (line = in.readLine()) != null) {
                        try {
                            inbound.add(Envelope.decode(line));
                        } catch (IllegalArgumentException ex) {
                            ModInfo.LOG.warn("Ignoring malformed link message: {}", ex.getMessage());
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                if (running) {
                    String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                    if (!msg.equals(lastError)) {
                        ModInfo.LOG.warn("Manager link at {} unavailable ({}); retrying with backoff", address, msg);
                        lastError = msg;
                    }
                }
            } finally {
                if (linked) {
                    ModInfo.LOG.info("Manager link closed");
                }
                linked = false;
                socket = null;
                closeSocket(s);
                if (writer != null) {
                    writer.interrupt();
                }
            }
            if (!running) {
                break;
            }
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException ex) {
                if (!running) {
                    break;
                }
            }
            backoff = Math.min(backoff * 2, Protocol.LINK_RECONNECT_MAX_MS);
        }
    }

    private Thread startWriter(Socket s, OutputStream out) {
        Thread w = new Thread(() -> {
            while (running && !s.isClosed()) {
                String line;
                try {
                    line = outbox.pollFirst(500, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    return;
                }
                if (line == null) {
                    continue;
                }
                try {
                    LineCodec.writeLine(out, line, Protocol.MAX_LINE_BYTES);
                } catch (LineCodec.LineTooLongException e) {
                    ModInfo.LOG.error("Dropping link message over {} bytes", Protocol.MAX_LINE_BYTES);
                } catch (IOException e) {
                    // Keep the line for the next connection; the reader notices the dead socket and reconnects.
                    outbox.offerFirst(line);
                    closeSocket(s);
                    return;
                }
            }
        }, "BaritoneBots-link-writer");
        w.setDaemon(true);
        w.start();
        return w;
    }

    private static void closeSocket(Socket s) {
        if (s == null) {
            return;
        }
        try {
            s.close();
        } catch (IOException ignored) {
            // closing a broken socket
        }
    }
}
