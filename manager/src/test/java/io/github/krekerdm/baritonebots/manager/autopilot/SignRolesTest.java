package io.github.krekerdm.baritonebots.manager.autopilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.krekerdm.baritonebots.manager.config.ManagerConfig;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class SignRolesTest {
    private static final Map<String, String> WORDS = ManagerConfig.AutopilotCfg.defaults().signWords();
    private static final Function<String, String> CATS = new Categories(
            ManagerConfig.AutopilotCfg.defaults().categories(), null)::of;

    @Test
    void russianAndEnglishDefaults() {
        assertEquals(List.of("storage"), SignRoles.roles("склад", null, WORDS, CATS));
        assertEquals(List.of("inbox"), SignRoles.roles("Приём", null, WORDS, CATS), "case and ё do not matter");
        assertEquals(List.of("inbox"), SignRoles.roles("прием", null, WORDS, CATS));
        assertEquals(List.of("kit"), SignRoles.roles("KIT", null, WORDS, CATS));
        assertEquals(List.of("trash"), SignRoles.roles("мусор", null, WORDS, CATS));
        assertEquals(List.of("sorted:ores_ingots"), SignRoles.roles("руда", null, WORDS, CATS));
        assertEquals(List.of("sorted:wood"), SignRoles.roles("Wood", null, WORDS, CATS));
        assertEquals(List.of("sorted:stone_building"), SignRoles.roles("камень", null, WORDS, CATS));
        assertEquals(List.of("sorted:food"), SignRoles.roles("еда", null, WORDS, CATS));
        assertEquals(List.of("sorted:tools_armor"), SignRoles.roles("инструменты", null, WORDS, CATS));
        assertEquals(List.of("sorted:redstone"), SignRoles.roles("редстоун", null, WORDS, CATS));
        assertEquals(List.of("sorted:farming"), SignRoles.roles("ферма", null, WORDS, CATS));
        assertEquals(List.of("sorted:mob_drops"), SignRoles.roles("мобы", null, WORDS, CATS));
        assertEquals(List.of("sorted:misc"), SignRoles.roles("разное", null, WORDS, CATS));
    }

    @Test
    void wordFormsCombinationsAndNoise() {
        assertEquals(List.of("storage"), SignRoles.roles("СКЛАДА №2", null, WORDS, CATS), "longer word, 4+ letter key");
        assertEquals(List.of("storage", "sorted:ores_ingots"), SignRoles.roles("склад руды и слитки", null, WORDS, CATS),
                "every role plus the first category");
        assertEquals(List.of("sorted:food"), SignRoles.roles("еда дерево", null, WORDS, CATS), "first category wins");
        assertTrue(SignRoles.roles("привет, мир", null, WORDS, CATS).isEmpty());
        assertTrue(SignRoles.roles("китайский", null, WORDS, CATS).isEmpty(), "3-letter words match exactly only");
        assertTrue(SignRoles.roles(null, null, WORDS, CATS).isEmpty());
    }

    @Test
    void itemFramesGiveTheCategoryOfTheItem() {
        assertEquals(List.of("sorted:ores_ingots"), SignRoles.roles(null, "minecraft:iron_ingot", WORDS, CATS));
        assertEquals(List.of("sorted:food"), SignRoles.roles("", "minecraft:bread", WORDS, CATS));
        assertEquals(List.of("storage"), SignRoles.roles("склад", "minecraft:bread", WORDS, CATS),
                "a sign word beats the frame");
        assertEquals(List.of("sorted:redstone"), SignRoles.roles(null, "minecraft:repeater", Map.of(), CATS));
    }

    @Test
    void customDictionary() {
        Map<String, String> words = Map.of("алмазы", "ores_ingots", "Сдача", "inbox");
        assertEquals(List.of("inbox", "sorted:ores_ingots"), SignRoles.roles("алмазы сдача", null, words, CATS),
                "bare category names become sorted:<name>");
    }
}
