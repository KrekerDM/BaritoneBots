package io.github.krekerdm.baritonebots.plugin;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.logging.Logger;

/**
 * Logs verified bots in through AuthMe or nLogin, called by reflection so the plugin has no compile or load
 * dependency on either. Verified API (checked against the published jars):
 * <ul>
 *     <li>AuthMe 5.7.0 {@code fr.xephi.authme.api.v3.AuthMeApi}: static {@code getInstance()},
 *     {@code isAuthenticated(Player)}, {@code isRegistered(String)}, {@code forceLogin(Player)}.</li>
 *     <li>nLogin API 10.4 and 2.0 {@code com.nickuc.login.api.nLoginAPI}: static {@code getApi()},
 *     {@code isAuthenticated(String)}, {@code forceLogin(String, boolean)} (false when not registered).</li>
 * </ul>
 * Neither plugin can log in an account that is not registered; the bot mod registers itself in that case.
 * Server thread only.
 */
final class ForceLogin {
    static final String AUTHME = "AuthMe";
    static final String NLOGIN = "nLogin";

    enum Outcome {
        LOGGED_IN("logged_in"),
        ALREADY("already_authenticated"),
        NOT_REGISTERED("not_registered"),
        NO_AUTH_PLUGIN("no_auth_plugin"),
        API_MISSING("api_missing"),
        ERROR("error");

        final String code;

        Outcome(String code) {
            this.code = code;
        }
    }

    record Result(Outcome outcome, String plugin, String detail) {
        boolean ok() {
            return outcome == Outcome.LOGGED_IN || outcome == Outcome.ALREADY;
        }
    }

    private final Logger log;
    private Plugin bound;
    private Bridge bridge;
    private String bindError;

    ForceLogin(Logger log) {
        this.log = log;
    }

    /** Short state for {@code /baritonebots status}; null when no auth plugin is installed. */
    String describe() {
        Bridge b = resolve();
        if (bound == null) {
            return null;
        }
        String name = bound.getName() + " " + bound.getPluginMeta().getVersion();
        return b != null ? name : name + " (API not found: " + bindError + ")";
    }

    boolean available() {
        return resolve() != null;
    }

    Result attempt(Player p) {
        Bridge b = resolve();
        if (b == null) {
            return bound == null
                    ? new Result(Outcome.NO_AUTH_PLUGIN, null, null)
                    : new Result(Outcome.API_MISSING, bound.getName(), bindError);
        }
        try {
            return new Result(b.login(p), bound.getName(), null);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return new Result(Outcome.ERROR, bound.getName(), cause.getClass().getSimpleName() + ": " + cause.getMessage());
        } catch (ReflectiveOperationException | RuntimeException e) {
            return new Result(Outcome.ERROR, bound.getName(), e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** Binds to the enabled auth plugin, re-binding when it was reloaded (a new plugin instance). */
    private Bridge resolve() {
        Plugin authme = Bukkit.getPluginManager().getPlugin(AUTHME);
        Plugin nlogin = Bukkit.getPluginManager().getPlugin(NLOGIN);
        Plugin target = authme != null && authme.isEnabled() ? authme
                : nlogin != null && nlogin.isEnabled() ? nlogin : null;
        if (target == null) {
            bound = null;
            bridge = null;
            bindError = null;
            return null;
        }
        if (target == bound) {
            return bridge;
        }
        bound = target;
        bridge = null;
        bindError = null;
        try {
            bridge = target == authme ? AuthMeBridge.bind(target) : NLoginBridge.bind(target);
            log.info("Force-login will use " + target.getName() + " " + target.getPluginMeta().getVersion());
        } catch (ReflectiveOperationException | LinkageError e) {
            bindError = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.warning(target.getName() + " is installed but its force-login API was not found (" + bindError
                    + "); force-login is disabled. Update BaritoneBots or " + target.getName() + ".");
        }
        return bridge;
    }

    private interface Bridge {
        Outcome login(Player p) throws ReflectiveOperationException;
    }

    private record AuthMeBridge(Method getInstance, Method isAuthenticated, Method isRegistered,
                                Method forceLogin) implements Bridge {
        static AuthMeBridge bind(Plugin plugin) throws ReflectiveOperationException {
            Class<?> api = Class.forName("fr.xephi.authme.api.v3.AuthMeApi", true, plugin.getClass().getClassLoader());
            return new AuthMeBridge(api.getMethod("getInstance"), api.getMethod("isAuthenticated", Player.class),
                    api.getMethod("isRegistered", String.class), api.getMethod("forceLogin", Player.class));
        }

        @Override
        public Outcome login(Player p) throws ReflectiveOperationException {
            Object api = getInstance.invoke(null);
            if (api == null) {
                throw new IllegalStateException("AuthMeApi.getInstance() returned null (AuthMe not initialised)");
            }
            if ((Boolean) isAuthenticated.invoke(api, p)) {
                return Outcome.ALREADY;
            }
            if (!(Boolean) isRegistered.invoke(api, p.getName())) {
                return Outcome.NOT_REGISTERED;
            }
            forceLogin.invoke(api, p);
            return Outcome.LOGGED_IN;
        }
    }

    private record NLoginBridge(Method getApi, Method isAuthenticated, Method forceLogin) implements Bridge {
        static NLoginBridge bind(Plugin plugin) throws ReflectiveOperationException {
            Class<?> api = Class.forName("com.nickuc.login.api.nLoginAPI", true, plugin.getClass().getClassLoader());
            return new NLoginBridge(api.getMethod("getApi"), api.getMethod("isAuthenticated", String.class),
                    api.getMethod("forceLogin", String.class, boolean.class));
        }

        @Override
        public Outcome login(Player p) throws ReflectiveOperationException {
            Object api = getApi.invoke(null);
            if ((Boolean) isAuthenticated.invoke(api, p.getName())) {
                return Outcome.ALREADY;
            }
            return (Boolean) forceLogin.invoke(api, p.getName(), true) ? Outcome.LOGGED_IN : Outcome.NOT_REGISTERED;
        }
    }
}
