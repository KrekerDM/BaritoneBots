package io.github.krekerdm.baritonebots.mod.behaviour;

import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.BotConfig;
import io.github.krekerdm.baritonebots.common.msg.EventKinds;
import io.github.krekerdm.baritonebots.common.msg.Levels;
import io.github.krekerdm.baritonebots.mod.BotRuntime;
import io.github.krekerdm.baritonebots.mod.util.RateLimiter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Auto-login for AuthMe/nLogin style servers (SPEC §2.5 {@code login}): system messages are matched against the
 * configured regexes; a register/login prompt sends the matching command after {@code delayMs} (at most one
 * command per 10 s, 4 in total), success/failure lines raise {@code login_ok}/{@code login_failed}, and
 * {@code joinCommands} run once logged in. System lines that match nothing are forwarded as {@code chat} events.
 */
public final class LoginBehaviour {
    private static final long COMMAND_COOLDOWN_MS = 10_000;
    private static final long ASSUME_OK_AFTER_MS = 10_000;
    private static final long NO_PROMPT_AFTER_MS = 15_000;
    private static final int MAX_ATTEMPTS = 4;
    private static final long JOIN_COMMAND_SPACING_MS = 1_000;

    private enum State { OFFLINE, WAITING, SENT, DONE, FAILED }

    private final BotRuntime bot;
    private final RateLimiter chatLimiter = new RateLimiter(10, 10_000);
    private List<Pattern> loginPatterns = List.of();
    private List<Pattern> registerPatterns = List.of();
    private List<Pattern> successPatterns = List.of();
    private List<Pattern> failurePatterns = List.of();
    private State state = State.OFFLINE;
    private long joinedAt;
    private long lastSentAt;
    private int attempts;
    private String pendingCommand;
    private long pendingAt;
    private final Deque<String> joinQueue = new ArrayDeque<>();
    private long nextJoinCommandAt;

    public LoginBehaviour(BotRuntime bot) {
        this.bot = bot;
        onConfig(bot.config());
    }

    public void onConfig(BotConfig cfg) {
        BotConfig.Login l = cfg.login();
        loginPatterns = compile(l.loginPatterns());
        registerPatterns = compile(l.registerPatterns());
        successPatterns = compile(l.successPatterns());
        failurePatterns = compile(l.failurePatterns());
    }

    private List<Pattern> compile(List<String> raw) {
        List<Pattern> out = new ArrayList<>();
        for (String r : raw) {
            try {
                out.add(Pattern.compile(r));
            } catch (PatternSyntaxException e) {
                bot.warn("invalid login pattern '" + r + "': " + e.getDescription());
            }
        }
        return out;
    }

    /** True while waiting for or answering an auth prompt (status state {@code logging_in}). */
    public boolean isLoggingIn() {
        return state == State.WAITING || state == State.SENT;
    }

    public void onJoin() {
        BotConfig.Login l = bot.config().login();
        joinedAt = System.currentTimeMillis();
        attempts = 0;
        pendingCommand = null;
        joinQueue.clear();
        if (BotConfig.Login.MODE_AUTO.equals(l.mode()) && l.password() != null && !l.password().isEmpty()) {
            state = State.WAITING;
        } else {
            loggedIn();
        }
    }

    public void onLeave() {
        state = State.OFFLINE;
        pendingCommand = null;
        joinQueue.clear();
    }

    /**
     * A system message (client thread). Action-bar lines ({@code overlay}) are only checked for auth prompts,
     * never forwarded as {@code chat}.
     */
    public void onSystemMessage(String text, boolean overlay) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (isLoggingIn() && handleAuthLine(text)) {
            return;
        }
        if (!overlay && chatLimiter.tryAcquire()) {
            bot.event(EventKinds.CHAT, Levels.INFO, text, Json.obj("text", text));
        }
    }

    private boolean handleAuthLine(String text) {
        BotConfig.Login l = bot.config().login();
        if (matches(failurePatterns, text)) {
            state = State.FAILED;
            pendingCommand = null;
            bot.event(EventKinds.LOGIN_FAILED, Levels.ERROR, "login failed: " + text, Json.obj("text", text));
            bot.status.markDirty();
            return true;
        }
        if (matches(successPatterns, text)) {
            bot.event(EventKinds.LOGIN_OK, Levels.INFO, "logged in", Json.obj("text", text));
            loggedIn();
            return true;
        }
        if (matches(registerPatterns, text)) {
            schedule(l.registerCommand());
            return true;
        }
        if (matches(loginPatterns, text)) {
            schedule(l.loginCommand());
            return true;
        }
        return false;
    }

    private void schedule(String template) {
        long now = System.currentTimeMillis();
        if (pendingCommand != null || now - lastSentAt < COMMAND_COOLDOWN_MS) {
            return;
        }
        if (attempts >= MAX_ATTEMPTS) {
            state = State.FAILED;
            bot.event(EventKinds.LOGIN_FAILED, Levels.ERROR, "login failed: no success after " + attempts + " attempts",
                    Json.obj("attempts", attempts));
            return;
        }
        pendingCommand = template.replace("{password}", bot.config().login().password());
        pendingAt = now + Math.max(0, bot.config().login().delayMs());
        state = State.SENT;
    }

    private void loggedIn() {
        state = State.DONE;
        joinQueue.clear();
        joinQueue.addAll(bot.config().login().joinCommands());
        nextJoinCommandAt = System.currentTimeMillis() + Math.max(0, bot.config().login().delayMs());
        bot.status.markDirty();
    }

    public void tick() {
        if (state == State.OFFLINE || !bot.inGame()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (pendingCommand != null && now >= pendingAt) {
            bot.connection.chat(pendingCommand);
            bot.log.info("sent auth command (attempt " + (attempts + 1) + ")");
            pendingCommand = null;
            attempts++;
            lastSentAt = now;
        }
        if (state == State.SENT && pendingCommand == null && now - lastSentAt > ASSUME_OK_AFTER_MS) {
            bot.event(EventKinds.LOGIN_OK, Levels.INFO, "logged in (no confirmation line seen)",
                    Json.obj("assumed", true));
            loggedIn();
        } else if (state == State.WAITING && now - joinedAt > NO_PROMPT_AFTER_MS) {
            loggedIn(); // no auth plugin prompt: nothing to do
        }
        if (state == State.DONE && !joinQueue.isEmpty() && now >= nextJoinCommandAt) {
            bot.connection.chat(joinQueue.poll());
            nextJoinCommandAt = now + JOIN_COMMAND_SPACING_MS;
        }
    }

    private static boolean matches(List<Pattern> patterns, String text) {
        for (Pattern p : patterns) {
            if (p.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }
}
