package io.github.krekerdm.baritonebots.manager.events;

import com.sun.net.httpserver.HttpExchange;
import io.github.krekerdm.baritonebots.common.json.Json;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Server-sent events fan-out for {@code /api/stream} (SPEC §6). Each client has its own bounded queue drained by
 * its HTTP handler thread, so a slow browser never blocks the manager loop; a client whose queue overflows is
 * dropped (the panel reconnects and reloads the snapshot). Heartbeat comment every 15 s.
 */
public final class SseHub {
    /** Event names the panel listens to. */
    public static final String BOT = "bot";
    public static final String QUEUE = "queue";
    public static final String PROCESS = "process";
    public static final String EVENT = "event";
    public static final String PROJECT = "project";
    public static final String RUNTIME = "runtime";
    public static final String LOG = "log";
    /** Not in SPEC §6; world knowledge changed (container snapshot, discover). Unknown names are ignored by browsers. */
    public static final String WORLD = "world";
    /** AI supervisor feed entries and AI on/off (SPEC §5.7d). */
    public static final String AI = "ai";

    private static final long HEARTBEAT_MS = 15_000;
    private static final int QUEUE_CAPACITY = 2000;
    private static final String CLOSE = "\u0000close";

    private final Set<Client> clients = ConcurrentHashMap.newKeySet();

    /** Thread-safe; the payload is serialised once. */
    public void broadcast(String event, Object data) {
        if (clients.isEmpty()) {
            return;
        }
        String frame = "event: " + event + "\ndata: " + Json.toJson(Json.toTree(data)) + "\n\n";
        for (Client c : clients) {
            if (!c.queue.offer(frame)) {
                c.queue.clear();
                c.queue.offer(CLOSE);
            }
        }
    }

    public int clientCount() {
        return clients.size();
    }

    /** Serves one SSE connection until the client goes away; blocks the calling (virtual) thread. */
    public void serve(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("X-Accel-Buffering", "no");
        ex.sendResponseHeaders(200, 0);
        Client client = new Client();
        clients.add(client);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(": connected\nretry: 3000\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            while (true) {
                String frame = client.queue.poll(HEARTBEAT_MS, TimeUnit.MILLISECONDS);
                if (CLOSE.equals(frame)) {
                    break;
                }
                out.write((frame == null ? ": ping\n\n" : frame).getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // browser closed the tab
        } finally {
            clients.remove(client);
            ex.close();
        }
    }

    /** Ends every stream (shutdown). */
    public void closeAll() {
        for (Client c : clients) {
            c.queue.clear();
            c.queue.offer(CLOSE);
        }
    }

    private static final class Client {
        final BlockingQueue<String> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    }
}
