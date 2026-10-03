package io.github.krekerdm.baritonebots.manager.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.BotConfig;
import io.github.krekerdm.baritonebots.manager.runtime.HmcFiles;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Files written for HeadlessMC and the game directory. */
class LaunchFilesTest {
    @TempDir
    Path dir;

    @Test
    void propertiesSurviveJavaPropertiesLoading() throws IOException {
        Map<String, String> props = new LinkedHashMap<>();
        props.put("hmc.mcdir", "C:/Users/Some One/BaritoneBots-data/runtime/mc");
        props.put("hmc.jvmargs", "-Xmx1024m -XX:+UseG1GC -Dbaritonebots.link=127.0.0.1:25590 -Dbaritonebots.secret=a=b:c");
        props.put("hmc.gameargs", "--quickPlayMultiplayer mc.example.org:25565");
        props.put("hmc.java.versions", "C:\\Program Files\\Java\\jdk-25\\bin\\java.exe");
        Path f = dir.resolve("HeadlessMC").resolve("config.properties");
        HmcFiles.writeProperties(f, props);
        Properties loaded = new Properties();
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            loaded.load(r);
        }
        props.forEach((k, v) -> assertEquals(v, loaded.getProperty(k), k));
    }

    @Test
    void optionsSeedHasTheVersionLineAndClientLimits() {
        String seed = ProcessSupervisor.optionsSeed(new BotConfig.ClientOpts(true, true, true, 15, 6));
        assertTrue(seed.startsWith("version:4903\n"));
        assertTrue(seed.contains("renderDistance:6\n"));
        assertTrue(seed.contains("maxFps:15\n"));
    }

    @Test
    void baritoneSettingsUseBaritoneSyntax() {
        Map<String, JsonElement> s = new LinkedHashMap<>();
        s.put("allowSprint", Json.toTree(true));
        s.put("mineGoalUpdateInterval", Json.toTree(10.0));
        s.put("blockReachDistance", Json.toTree(4.5));
        s.put("acceptableThrowawayItems", Json.arr("minecraft:dirt", "minecraft:cobblestone"));
        s.put("bad name", Json.toTree(1));
        String text = ProcessSupervisor.baritoneSettings(s);
        assertTrue(text.contains("allowSprint true\n"));
        assertTrue(text.contains("mineGoalUpdateInterval 10\n"));
        assertTrue(text.contains("blockReachDistance 4.5\n"));
        assertTrue(text.contains("acceptableThrowawayItems minecraft:dirt,minecraft:cobblestone\n"));
        assertFalse(text.contains("bad name"));
    }

    @Test
    void jvmArgSplitting() {
        assertEquals(List.of("-Xss2m", "-Dfoo=bar"), ProcessSupervisor.splitArgs("  -Xss2m   -Dfoo=bar "));
        assertEquals(List.of(), ProcessSupervisor.splitArgs(null));
    }
}
