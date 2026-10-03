package io.github.krekerdm.baritonebots.manager.link;

import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.Protocol;
import io.github.krekerdm.baritonebots.common.link.LineCodec;
import io.github.krekerdm.baritonebots.common.msg.Envelope;
import io.github.krekerdm.baritonebots.common.msg.MessageTypes;
import io.github.krekerdm.baritonebots.common.msg.Query;
import io.github.krekerdm.baritonebots.common.msg.QueryResult;
import io.github.krekerdm.baritonebots.manager.util.Log;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One bot connection (SPEC §2). Thread-safe: {@link #send} only enqueues, a writer thread does the IO, so the
 * manager loop never blocks on a slow socket. Queries are matched to {@code result} replies by message id.
 */
public final class LinkSession {
    private static final AtomicLong SESSION_IDS = new AtomicLong();
    private static final int OUT_CAPACITY = 5000;
    private static final String POISON = "\u0000";

    private final long id = SESSION_IDS.incrementAndGet();
    private final Socket socket;
    private final LinkedBlockingQueue<String> out = new LinkedBlockingQueue<>(OUT_CAPACITY);
    private final Map<String, CompletableFuture<QueryResult>> pending = new ConcurrentHashMap<>();
    private final AtomicLong messageIds = new AtomicLong();
    private volatile String botId;
    private volatile boolean accepted;
    private volatile boolean closed;

    LinkSession(Socket socket) {
        this.socket = socket;
    }

    public long id() {
        return id;
    }

    public String remote() {
        return String.valueOf(socket.getRemoteSocketAddress());
    }

    /** Bot id from {@code hello}; set before the handshake decision. */
    public String botId() {
        return botId;
    }

    void botId(String id) {
        this.botId = id;
    }

    /** True after the manager sent {@code welcome}. */
    public boolean accepted() {
        return accepted;
    }

    public void accept() {
        accepted = true;
    }

    public boolean closed() {
        return closed;
    }

    /** Queues a message; a session whose outbound queue overflows is closed (the bot reconnects). */
    public void send(Envelope e) {
        if (closed) {
            return;
        }
        if (!out.offer(e.encode())) {
            Log.warn("link %s (%s): outbound queue full, closing", botId, remote());
            close();
        }
    }

    public void send(String type, Object payload) {
        send(Envelope.of(type, payload));
    }

    /** Sends a {@code query}; the future fails with a timeout or when the link closes. */
    public CompletableFuture<QueryResult> query(String kind, JsonObject args, long timeoutMs) {
        String msgId = "m-" + messageIds.incrementAndGet();
        CompletableFuture<QueryResult> f = new CompletableFuture<>();
        if (closed) {
            f.completeExceptionally(new IOException("link closed"));
            return f;
        }
        pending.put(msgId, f);
        f.orTimeout(timeoutMs, TimeUnit.MILLISECONDS).whenComplete((r, t) -> pending.remove(msgId));
        send(Envelope.request(MessageTypes.QUERY, msgId, new Query(kind, args)));
        return f;
    }

    /** Called by the reader thread for {@code result} messages. */
    void onResult(Envelope e) {
        CompletableFuture<QueryResult> f = e.re() == null ? null : pending.remove(e.re());
        if (f != null) {
            try {
                f.complete(e.payload(QueryResult.class));
            } catch (RuntimeException ex) {
                f.completeExceptionally(ex);
            }
        }
    }

    void writerLoop() {
        try {
            OutputStream os = socket.getOutputStream();
            while (!closed) {
                String line = out.poll(30, TimeUnit.SECONDS);
                if (line == null) {
                    continue;
                }
                if (POISON.equals(line)) {
                    break;
                }
                LineCodec.writeLine(os, line, Protocol.MAX_LINE_BYTES);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            if (!closed) {
                Log.warn("link %s: write failed: %s", botId, e.getMessage());
            }
        } finally {
            close();
        }
    }

    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        out.clear();
        out.offer(POISON);
        try {
            socket.close();
        } catch (IOException ignored) {
            // already gone
        }
        IOException gone = new IOException("link closed");
        pending.values().forEach(f -> f.completeExceptionally(gone));
        pending.clear();
    }
}
