package io.github.krekerdm.baritonebots.mod.util;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads private fields by name. 26.x runs with Mojang names at runtime, so this is stable within a Minecraft
 * version and degrades to {@code null} (never an exception) when a field is renamed.
 */
public final class Reflect {
    private static final Map<String, Field> CACHE = new ConcurrentHashMap<>();
    private static final Field MISSING;

    static {
        try {
            MISSING = Reflect.class.getDeclaredField("CACHE");
        } catch (NoSuchFieldException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private Reflect() {
    }

    /** Value of field {@code name} declared on the object's class or a superclass, or {@code null}. */
    public static Object get(Object target, String name) {
        if (target == null) {
            return null;
        }
        Class<?> type = target.getClass();
        Field f = CACHE.computeIfAbsent(type.getName() + "#" + name, k -> find(type, name));
        if (f == MISSING) {
            return null;
        }
        try {
            return f.get(target);
        } catch (IllegalAccessException | RuntimeException e) {
            return null;
        }
    }

    private static Field find(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException | RuntimeException ignored) {
                // try the superclass
            }
        }
        return MISSING;
    }
}
