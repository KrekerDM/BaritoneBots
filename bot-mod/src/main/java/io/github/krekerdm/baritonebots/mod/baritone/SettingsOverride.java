package io.github.krekerdm.baritonebots.mod.baritone;

import baritone.api.Settings;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Temporary Baritone setting changes for one task: {@link #set} remembers the value before the first change,
 * {@link #restore} puts every original value back (call it from the task's {@code cleanup}).
 */
public final class SettingsOverride {
    private final Map<Settings.Setting<?>, Object> originals = new LinkedHashMap<>();

    public <T> void set(Settings.Setting<T> setting, T value) {
        if (!originals.containsKey(setting)) {
            originals.put(setting, setting.value);
        }
        setting.value = value;
    }

    public boolean isEmpty() {
        return originals.isEmpty();
    }

    @SuppressWarnings("unchecked")
    public void restore() {
        originals.forEach((s, v) -> ((Settings.Setting<Object>) s).value = v);
        originals.clear();
    }
}
