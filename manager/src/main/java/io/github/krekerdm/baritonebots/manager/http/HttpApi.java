package io.github.krekerdm.baritonebots.manager.http;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.manager.Manager;
import io.github.krekerdm.baritonebots.manager.ManagerLoop;
import io.github.krekerdm.baritonebots.manager.config.ConfigStore;
import io.github.krekerdm.baritonebots.manager.config.ValidationException;
import io.github.krekerdm.baritonebots.manager.tasks.TaskCatalog;
import io.github.krekerdm.baritonebots.manager.util.Log;
import io.github.krekerdm.baritonebots.manager.util.Tokens;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * JDK HttpServer on {@code general.panel.bind:port} (SPEC §6): bearer-token auth on every {@code /api} route
 * (constant-time compare; {@code /api/stream} also takes {@code ?token=}), JSON errors, SSE, and the static panel
 * from the {@code /panel/} resource folder. Handlers run on virtual threads and reach loop state via
 * {@link ManagerLoop#await}.
 */
public final class HttpApi {
    /** Returned by handlers that wrote the response themselves (SSE). */
    static final Object HANDLED = new Object();
    private static final Pattern STATIC_PATH = Pattern.compile("[A-Za-z0-9._/-]+");
    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"), Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("mjs", "text/javascript; charset=utf-8"), Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("json", "application/json; charset=utf-8"), Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"), Map.entry("ico", "image/x-icon"), Map.entry("webp", "image/webp"),
            Map.entry("woff2", "font/woff2"), Map.entry("woff", "font/woff"), Map.entry("txt", "text/plain; charset=utf-8"),
            Map.entry("map", "application/json; charset=utf-8"), Map.entry("webmanifest", "application/manifest+json"));
    private static final String NOT_BUILT = """
            <!doctype html><html><head><meta charset="utf-8"><title>BaritoneBots</title></head>
            <body style="font-family:sans-serif;max-width:40em;margin:3em auto">
            <h1>BaritoneBots manager</h1>
            <p>The web panel is not built into this jar yet. The manager and its HTTP API (<code>/api</code>) are running.</p>
            <p>Панель ещё не собрана в этот jar. Менеджер и HTTP API (<code>/api</code>) работают.</p>
            </body></html>
            """;

    private final Manager m;
    private final Router router = new Router();
    private HttpServer server;
    private ExecutorService executor;

    public HttpApi(Manager m) {
        this.m = m;
        new ApiRoutes(m, new Schematics(m.dataDir.resolve("schematics"))).register(router);
    }

    public void start(String bind, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(bind, port), 64);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
        Log.info("panel listening on %s:%d", bind, port);
    }

    public void stop() {
        if (server != null) {
            m.sse.closeAll();
            server.stop(1);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void handle(HttpExchange ex) {
        try {
            String path = ex.getRequestURI().getRawPath();
            if (path.equals("/api") || path.startsWith("/api/")) {
                api(ex, path);
            } else {
                serveStatic(ex, path);
            }
        } catch (IOException e) {
            // client went away
        } catch (RuntimeException e) {
            Log.error("http handler crashed", e);
        } finally {
            ex.close();
        }
    }

    private void api(HttpExchange ex, String path) throws IOException {
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        String method = ex.getRequestMethod().toUpperCase(Locale.ROOT);
        if (!authorized(ex, path)) {
            error(ex, 401, "unauthorized", "missing or wrong panel token");
            return;
        }
        Router.Match match = router.find(method, path);
        if (match == null) {
            error(ex, 404, "not_found", "no such endpoint: " + method + " " + path);
            return;
        }
        if (match.handler() == null) {
            error(ex, 405, "method_not_allowed", method + " is not allowed here");
            return;
        }
        try {
            Object result = match.handler().handle(new Req(ex, match.params()));
            if (result == HANDLED) {
                return;
            }
            int status = 200;
            if (result instanceof Status s) {
                status = s.code();
                result = s.body();
            }
            JsonElement body = result == null ? Json.obj("ok", true) : Json.toTree(result);
            send(ex, status, "application/json; charset=utf-8", Json.toJson(body).getBytes(StandardCharsets.UTF_8));
        } catch (ApiException e) {
            error(ex, e.status(), e.code(), e.getMessage());
        } catch (ValidationException e) {
            JsonObject o = Json.obj("error", "validation", "message", e.getMessage(), "fields", Json.toTree(e.fields()));
            send(ex, 400, "application/json; charset=utf-8", Json.toJson(o).getBytes(StandardCharsets.UTF_8));
        } catch (TaskCatalog.BadArgsException e) {
            JsonObject o = Json.obj("error", "bad_args", "message", e.getMessage(), "type", e.type(), "arg", e.arg(),
                    "code", e.code());
            send(ex, 400, "application/json; charset=utf-8", Json.toJson(o).getBytes(StandardCharsets.UTF_8));
        } catch (ConfigStore.NoSuchItemException e) {
            error(ex, 404, "not_found", e.getMessage());
        } catch (ManagerLoop.LoopTimeoutException e) {
            error(ex, 503, "busy", e.getMessage());
        } catch (JsonParseException | IllegalArgumentException e) {
            error(ex, 400, "bad_request", e.getMessage());
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            Log.error("API " + method + " " + path + " failed", e);
            error(ex, 500, "internal", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /** A JSON body with a non-200 status. */
    record Status(int code, Object body) {
    }

    private boolean authorized(HttpExchange ex, String path) {
        String token = null;
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            token = auth.substring(7).trim();
        }
        if (token == null && path.equals("/api/stream")) {
            String q = ex.getRequestURI().getRawQuery();
            if (q != null) {
                for (String pair : q.split("&")) {
                    if (pair.startsWith("token=")) {
                        token = Router.decode(pair.substring(6));
                    }
                }
            }
        }
        return Tokens.constantTimeEquals(m.secrets.panelToken(), token);
    }

    static void error(HttpExchange ex, int status, String code, String message) throws IOException {
        JsonObject o = Json.obj("error", code, "message", message);
        send(ex, status, "application/json; charset=utf-8", Json.toJson(o).getBytes(StandardCharsets.UTF_8));
    }

    static void send(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        boolean head = "HEAD".equalsIgnoreCase(ex.getRequestMethod());
        ex.sendResponseHeaders(status, head || body.length == 0 ? -1 : body.length);
        if (!head && body.length > 0) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        }
    }

    // ------------------------------------------------------------------ static panel

    private void serveStatic(HttpExchange ex, String rawPath) throws IOException {
        String method = ex.getRequestMethod().toUpperCase(Locale.ROOT);
        if (!method.equals("GET") && !method.equals("HEAD")) {
            send(ex, 405, "text/plain; charset=utf-8", "method not allowed".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String rel = Router.decode(rawPath);
        rel = rel.startsWith("/") ? rel.substring(1) : rel;
        if (rel.isEmpty() || rel.endsWith("/")) {
            rel = rel + "index.html";
        }
        String last = rel.substring(rel.lastIndexOf('/') + 1);
        int dot = last.lastIndexOf('.');
        String ext = dot < 0 ? "" : last.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!STATIC_PATH.matcher(rel).matches() || rel.contains("..") || rel.contains("//") || !TYPES.containsKey(ext)) {
            send(ex, 404, "text/plain; charset=utf-8", "not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        ex.getResponseHeaders().set("X-Frame-Options", "DENY");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        byte[] data;
        try (InputStream in = HttpApi.class.getClassLoader().getResourceAsStream("panel/" + rel)) {
            data = in == null ? null : in.readAllBytes();
        }
        if (data == null) {
            if (rel.equals("index.html")) {
                send(ex, 200, TYPES.get("html"), NOT_BUILT.getBytes(StandardCharsets.UTF_8));
            } else {
                send(ex, 404, "text/plain; charset=utf-8", "not found".getBytes(StandardCharsets.UTF_8));
            }
            return;
        }
        send(ex, 200, TYPES.get(ext), data);
    }
}
