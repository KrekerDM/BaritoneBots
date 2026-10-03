package io.github.krekerdm.baritonebots.mod.lowpower;

import io.github.krekerdm.baritonebots.common.msg.BotConfig;
import io.github.krekerdm.baritonebots.mod.LaunchProps;
import io.github.krekerdm.baritonebots.mod.ModInfo;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.sounds.SoundSource;

import java.lang.reflect.Field;

/**
 * State and hooks for the low-power mixins (SPEC §4.4). Mixins only call the static {@code should*}/{@code on*}
 * methods; everything else is set from the link config on the client thread.
 * <ul>
 *   <li>{@code lowPower}: forces cheap options at {@code Options.<init>} and on every config, caps the loop at
 *       {@code maxFps} when headless;</li>
 *   <li>{@code skipRender}: skips {@code GameRenderer} extract/render when headless and no screen/overlay;</li>
 *   <li>{@code muteSounds}: cancels {@code SoundEngine} tick/play.</li>
 * </ul>
 * Before the first config the defaults of {@link BotConfig.ClientOpts} apply when the client was started by the
 * manager (link set) or with {@code -Dbaritonebots.headless=true}; a plain hand-started client is left alone.
 */
public final class LowPower {
    private static volatile boolean headless;
    private static volatile boolean lowPower;
    private static volatile boolean skipRender;
    private static volatile boolean muteSounds;
    private static volatile int maxFps;
    private static volatile int renderDistance;

    private static boolean renderHookSeen;
    private static int pendingDecision; // 0 none, 1 skip, 2 render (decided at extract for the same frame)
    private static long lastFrameEndNanos;
    private static Field optionValueField;
    private static boolean optionFieldFailed;

    private LowPower() {
    }

    private static volatile boolean initialised;

    public static void init(LaunchProps props) {
        initialised = true;
        headless = props.headless();
        if (props.linked() || props.headless()) {
            set(BotConfig.ClientOpts.defaults());
        } else {
            set(new BotConfig.ClientOpts(false, false, false, 0, 0));
        }
    }

    private static void set(BotConfig.ClientOpts o) {
        lowPower = o.lowPower();
        skipRender = o.skipRender();
        muteSounds = o.muteSounds();
        maxFps = o.maxFps();
        renderDistance = o.renderDistance();
    }

    public static boolean headless() {
        return headless;
    }

    /** New config from the manager; client thread. Re-applies the forced options to the live game. */
    public static void apply(Minecraft mc, BotConfig.ClientOpts o) {
        set(o);
        if (lowPower && mc.options != null) {
            Options opts = mc.options;
            put(opts.renderDistance(), clamp(renderDistance, 2, 32), false);
            put(opts.framerateLimit(), clamp(maxFps, 10, 260), false);
            forceStatic(opts, false);
        }
    }

    /** {@code Options.<init>} RETURN (after options.txt was read). Writes values without change callbacks. */
    public static void onOptionsLoaded(Options opts) {
        if (!initialised) {
            init(LaunchProps.fromSystem()); // Options built before the entrypoint ran
        }
        if (!lowPower) {
            return;
        }
        setRaw(opts.renderDistance(), clamp(renderDistance, 2, 32));
        setRaw(opts.framerateLimit(), clamp(maxFps, 10, 260));
        forceStatic(opts, true);
    }

    private static void forceStatic(Options opts, boolean raw) {
        put(opts.particles(), ParticleStatus.MINIMAL, raw);
        put(opts.cloudStatus(), CloudStatus.OFF, raw);
        for (SoundSource s : SoundSource.values()) {
            put(opts.getSoundSourceOptionInstance(s), 0.0, raw);
        }
        opts.pauseOnLostFocus = false;
        opts.onboardAccessibility = false;
        opts.tutorialStep = TutorialSteps.NONE;
        opts.skipMultiplayerWarning = true;
        opts.joinedFirstServer = true;
    }

    private static <T> void put(OptionInstance<T> opt, T value, boolean raw) {
        if (raw) {
            setRaw(opt, value);
            return;
        }
        try {
            opt.set(value);
        } catch (RuntimeException e) {
            ModInfo.LOG.debug("Cannot set option: {}", e.toString());
        }
    }

    /** {@code OptionInstance.set} runs callbacks that touch half-constructed game objects during startup. */
    private static <T> void setRaw(OptionInstance<T> opt, T value) {
        if (opt == null || optionFieldFailed) {
            return;
        }
        try {
            if (optionValueField == null) {
                optionValueField = OptionInstance.class.getDeclaredField("value");
                optionValueField.setAccessible(true);
            }
            optionValueField.set(opt, value);
        } catch (ReflectiveOperationException | RuntimeException e) {
            optionFieldFailed = true;
            ModInfo.LOG.warn("Low-power options disabled: {}", e.toString());
        }
    }

    private static boolean skipNow(Minecraft mc) {
        return headless && skipRender && mc.gui != null && mc.gui.screen() == null && mc.gui.overlay() == null;
    }

    /** {@code GameRenderer#extract} HEAD. Only skips once the render hook is known to work (same decision). */
    public static boolean shouldSkipExtract() {
        if (!renderHookSeen) {
            return false;
        }
        boolean skip = skipNow(Minecraft.getInstance());
        pendingDecision = skip ? 1 : 2;
        return skip;
    }

    /** {@code GameRenderer#render} HEAD. */
    public static boolean shouldSkipRender() {
        renderHookSeen = true;
        int d = pendingDecision;
        pendingDecision = 0;
        return d != 0 ? d == 1 : skipNow(Minecraft.getInstance());
    }

    /** {@code SoundEngine} tick/play HEAD. */
    public static boolean shouldMute() {
        return muteSounds;
    }

    /** {@code Minecraft#runTick} TAIL: sleeps so a headless client runs at most {@code maxFps} frames/s. */
    public static void afterFrame() {
        int fps = maxFps;
        if (!headless || !lowPower || fps <= 0) {
            return;
        }
        long frameNanos = 1_000_000_000L / Math.max(1, fps);
        long now = System.nanoTime();
        long wait = frameNanos - (now - lastFrameEndNanos);
        if (wait > 1_000_000L && wait <= frameNanos) {
            try {
                Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastFrameEndNanos = System.nanoTime();
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
