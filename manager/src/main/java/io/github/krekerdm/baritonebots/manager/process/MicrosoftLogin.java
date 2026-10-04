package io.github.krekerdm.baritonebots.manager.process;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.Levels;
import io.github.krekerdm.baritonebots.manager.Manager;
import io.github.krekerdm.baritonebots.manager.bots.BotState;
import io.github.krekerdm.baritonebots.manager.config.ManagerConfig;
import io.github.krekerdm.baritonebots.manager.http.ApiException;
import io.github.krekerdm.baritonebots.manager.runtime.HmcFiles;
import io.github.krekerdm.baritonebots.manager.util.AtomicFiles;
import io.github.krekerdm.baritonebots.manager.util.Log;
import io.github.krekerdm.baritonebots.manager.util.Os;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Microsoft account login per bot (SPEC §5.4) through HeadlessMC's device-code flow: {@code java -jar
 * headlessmc-launcher.jar --command login} in {@code bots/<id>} prints {@code Go to
 * https://www.microsoft.com/link?otc=<code>} and then waits (HeadlessMC keeps the process alive for its login
 * thread) until the user confirmed the code in a browser; it then prints {@code Logged into account <name>
 * successfully!} and stores the refresh token in {@code bots/<id>/HeadlessMC/auth/.accounts.json}
 * ({@code hmc.store.accounts}, default on). Launching a Microsoft bot then omits {@code -offline}; HeadlessMC
 * refreshes the token on every game launch. Events: {@code microsoft_login_code}, {@code microsoft_login_ok},
 * {@code microsoft_login_failed}. Loop-owned except the reader / process threads, which post back.
 */
public final class MicrosoftLogin {
    static final long LOGIN_TIMEOUT_MS = 15 * 60_000;
    static final int TAIL = 30;
    private static final Pattern GO_TO = Pattern.compile("Go to (https?://\\S+)");
    private static final Pattern OK = Pattern.compile("Logged into account (\\S+) successfully");
    private static final Pattern FAILED = Pattern.compile("(Failed to login(?: with device code| with webview)?:\\s*.*"
            + "|You can't play the game without an account.*|.*doesn't own (?:the game|Minecraft).*)");

    /** One parsed output line: {@code code} (url + code), {@code ok} (account) or {@code failed} (error). */
    public record Line(String kind, String url, String code, String account, String error) {
    }

    /** Parses a HeadlessMC output line of the login flow; null for anything else. Pure. */
    public static Line parse(String line) {
        if (line == null) {
            return null;
        }
        Matcher g = GO_TO.matcher(line);
        if (g.find()) {
            String url = g.group(1);
            String code = null;
            int i = url.indexOf("otc=");
            if (i >= 0) {
                String rest = url.substring(i + 4);
                int amp = rest.indexOf('&');
                code = URLDecoder.decode(amp >= 0 ? rest.substring(0, amp) : rest, StandardCharsets.UTF_8);
            }
            return new Line("code", url, code, null, null);
        }
        Matcher ok = OK.matcher(line);
        if (ok.find()) {
            return new Line("ok", null, null, ok.group(1), null);
        }
        Matcher f = FAILED.matcher(line);
        if (f.find()) {
            return new Line("failed", null, null, null, f.group(1).trim());
        }
        return null;
    }

    private static final class Session {
        final String botId;
        final long startedAt = System.currentTimeMillis();
        final CompletableFuture<JsonObject> code = new CompletableFuture<>();
        final Deque<String> tail = new ArrayDeque<>();
        Process process;
        ScheduledFuture<?> timeout;
        String state = "starting"; // starting | waiting | ok | failed | cancelled
        String url;
        String userCode;
        String account;
        String error;

        Session(String botId) {
            this.botId = botId;
        }

        boolean running() {
            return "starting".equals(state) || "waiting".equals(state);
        }
    }

    private final Manager m;
    private final Map<String, Session> sessions = new HashMap<>();

    public MicrosoftLogin(Manager m) {
        this.m = m;
    }

    /** HeadlessMC's account store of a bot. */
    public Path accountsFile(String botId) {
        return m.supervisor.botDir(botId).resolve("HeadlessMC").resolve("auth").resolve(".accounts.json");
    }

    /** A stored Microsoft account exists for the bot (non-empty HeadlessMC account list). */
    public boolean hasAccount(String botId) {
        Path f = accountsFile(botId);
        try {
            if (!Files.isRegularFile(f)) {
                return false;
            }
            JsonElement e = AtomicFiles.readJson(f);
            return e != null && (e.isJsonArray() && !e.getAsJsonArray().isEmpty()
                    || e.isJsonObject() && !e.getAsJsonObject().isEmpty());
        } catch (IOException | RuntimeException ex) {
            return false;
        }
    }

    /**
     * Starts the device-code login (or returns the running one). The future completes with {@code {url, code}} as
     * soon as HeadlessMC printed them; completion of the login is reported as events.
     */
    public CompletableFuture<JsonObject> start(BotState b) {
        if (!b.def.microsoft()) {
            throw ApiException.badRequest("not_microsoft", "set the bot's account type to 'microsoft' first");
        }
        Session running = sessions.get(b.id);
        if (running != null && running.running()) {
            return running.code;
        }
        ManagerConfig cfg = m.config.get();
        Path jar = m.installer.hmcJar(cfg);
        if (!Files.isRegularFile(jar)) {
            throw ApiException.conflict("not_installed", "HeadlessMC is not installed yet; install the runtime first");
        }
        Path java = m.installer.javaFor(cfg);
        List<String> command = new ArrayList<>(List.of(java.toString(), "-Xmx64m", "-XX:+UseSerialGC"));
        command.addAll(Os.childJvmFlags());
        command.addAll(List.of("-jar", jar.toAbsolutePath().toString(), "--command", "login"));
        Path dir = m.supervisor.botDir(b.id);
        Session s = new Session(b.id);
        sessions.put(b.id, s);
        s.timeout = m.loop.schedule(() -> {
            if (s.running()) {
                fail(s, "no confirmation within " + LOGIN_TIMEOUT_MS / 60_000 + " minutes");
                kill(s);
            }
        }, LOGIN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        Thread.ofVirtual().name("ms-login-" + b.id).start(() -> {
            try {
                Files.createDirectories(dir);
                Process p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
                p.getOutputStream().close();
                m.loop.post(() -> s.process = p);
                HmcFiles.pump(p.getInputStream(), line -> m.loop.post(() -> onLine(s, line)));
                int code = p.waitFor();
                m.loop.post(() -> onExit(s, code));
            } catch (IOException e) {
                m.loop.post(() -> fail(s, "cannot start HeadlessMC: " + e.getMessage()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        return s.code;
    }

    private void onLine(Session s, String line) {
        s.tail.addLast(line);
        while (s.tail.size() > TAIL) {
            s.tail.removeFirst();
        }
        Line l = parse(line);
        if (l == null || !s.running()) {
            return;
        }
        switch (l.kind()) {
            case "code" -> {
                s.state = "waiting";
                s.url = l.url();
                s.userCode = l.code();
                s.code.complete(Json.obj("url", l.url(), "code", l.code(), "state", s.state,
                        "expiresInSec", LOGIN_TIMEOUT_MS / 1000));
                m.event("microsoft_login_code", Levels.INFO, s.botId, "event.microsoft.code",
                        Map.of("bot", s.botId, "url", l.url(), "code", String.valueOf(l.code())),
                        Json.obj("url", l.url(), "code", l.code()));
            }
            case "ok" -> {
                s.state = "ok";
                s.account = l.account();
                cancelTimeout(s);
                BotState b = m.bots.get(s.botId);
                boolean nameDiffers = b != null && !l.account().equalsIgnoreCase(b.def.username());
                m.event("microsoft_login_ok", nameDiffers ? Levels.WARN : Levels.INFO, s.botId,
                        nameDiffers ? "event.microsoft.okRename" : "event.microsoft.ok",
                        Map.of("bot", s.botId, "account", l.account(), "username", b == null ? "" : b.def.username()),
                        Json.obj("account", l.account()));
                if (b != null) {
                    m.broadcastBot(b);
                }
            }
            default -> s.error = l.error();
        }
    }

    private void onExit(Session s, int code) {
        if (!s.running()) {
            return;
        }
        String last = s.tail.isEmpty() ? "" : s.tail.peekLast();
        fail(s, s.error != null ? s.error : "HeadlessMC exited with code " + code + " without logging in"
                + (last.isBlank() ? "" : " (" + last + ")"));
    }

    private void fail(Session s, String error) {
        if (!s.running()) {
            return;
        }
        s.state = "failed";
        s.error = error;
        cancelTimeout(s);
        s.code.completeExceptionally(new IllegalStateException(error));
        Log.warn("Microsoft login of %s failed: %s", s.botId, error);
        m.event("microsoft_login_failed", Levels.WARN, s.botId, "event.microsoft.failed",
                Map.of("bot", s.botId, "error", error), Json.obj("error", error));
    }

    private static void cancelTimeout(Session s) {
        if (s.timeout != null) {
            s.timeout.cancel(false);
            s.timeout = null;
        }
    }

    private static void kill(Session s) {
        Process p = s.process;
        if (p != null) {
            Thread.ofVirtual().start(() -> {
                p.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
            });
        }
    }

    /** {@code DELETE /api/bots/{id}/microsoft-login}: stops a running login. */
    public void cancel(BotState b) {
        Session s = sessions.get(b.id);
        if (s != null && s.running()) {
            s.state = "cancelled";
            cancelTimeout(s);
            s.code.completeExceptionally(new IllegalStateException("cancelled"));
            kill(s);
        }
    }

    /** Stops every running login (manager shutdown). */
    public void shutdown() {
        sessions.values().forEach(s -> {
            if (s.running()) {
                kill(s);
            }
        });
    }

    /** {@code GET /api/bots/{id}/microsoft-login}. */
    public JsonObject view(BotState b) {
        Session s = sessions.get(b.id);
        JsonObject o = Json.obj("microsoft", b.def.microsoft(), "hasAccount", hasAccount(b.id));
        if (s != null) {
            o.addProperty("state", s.state);
            o.addProperty("url", s.url);
            o.addProperty("code", s.userCode);
            o.addProperty("account", s.account);
            o.addProperty("error", s.error);
            o.addProperty("startedAt", s.startedAt);
        } else {
            o.addProperty("state", "idle");
        }
        return o;
    }
}
