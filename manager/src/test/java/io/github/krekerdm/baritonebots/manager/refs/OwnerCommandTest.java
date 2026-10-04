package io.github.krekerdm.baritonebots.manager.refs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class OwnerCommandTest {
    @Test
    void englishCommands() {
        OwnerCommand c = OwnerCommand.parse("build Castle.schem 90");
        assertEquals(OwnerCommand.BUILD, c.verb());
        assertEquals(List.of("Castle.schem", "90"), c.args(), "arguments keep their case");
        assertTrue(c.known());
        assertEquals(OwnerCommand.OBTAIN, OwnerCommand.parse("OBTAIN iron_ingot 32").verb());
        assertEquals("32", OwnerCommand.parse("obtain iron_ingot 32").arg(1));
        assertNull(OwnerCommand.parse("obtain").arg(0));
        assertEquals(OwnerCommand.HELP, OwnerCommand.parse("").verb(), "bare prefix = help");
        assertEquals(OwnerCommand.HELP, OwnerCommand.parse("  ").verb());
    }

    @Test
    void russianAliasesAndPhrases() {
        assertEquals(OwnerCommand.COME, OwnerCommand.parse("ко мне").verb());
        assertEquals(OwnerCommand.COME, OwnerCommand.parse("Ко мне @все").verb());
        assertEquals(OwnerCommand.TARGET_ALL, OwnerCommand.parse("Ко мне @все").target());
        assertEquals(OwnerCommand.FOLLOW, OwnerCommand.parse("за мной").verb());
        assertEquals(OwnerCommand.STOP, OwnerCommand.parse("стоп").verb());
        assertEquals(OwnerCommand.HERE, OwnerCommand.parse("точка шахта").verb());
        assertEquals(List.of("шахта"), OwnerCommand.parse("точка шахта").args());
        assertEquals(OwnerCommand.CHEST, OwnerCommand.parse("сундук руда").verb());
        assertEquals(OwnerCommand.BUILD, OwnerCommand.parse("построй замок").verb());
        assertEquals(OwnerCommand.TRASH, OwnerCommand.parse("мусор сдать").verb());
        assertEquals(OwnerCommand.OBTAIN, OwnerCommand.parse("добудь iron_ingot 10").verb());
        assertEquals(OwnerCommand.PROGRESS, OwnerCommand.parse("прогресс железо").verb());
        assertEquals("iron", OwnerCommand.tier("железо"));
        assertEquals("diamond", OwnerCommand.tier("DIAMOND"));
        assertNull(OwnerCommand.tier("gold"));
    }

    @Test
    void targetsAndUnknownVerbs() {
        OwnerCommand c = OwnerCommand.parse("trash @Bot2 store");
        assertEquals("Bot2", c.target());
        assertEquals(List.of("store"), c.args(), "the target word is not an argument");
        assertEquals(OwnerCommand.TARGET_ANY, OwnerCommand.parse("progress iron @any").target());
        OwnerCommand u = OwnerCommand.parse("dance now");
        assertFalse(u.known());
        assertEquals("dance", u.verb());
    }

    @Test
    void helpersForBuildAndTrash() {
        List<String> files = List.of("castle.schem", "Castle_big.litematic", "house.schem");
        assertEquals("castle.schem", OwnerCommands.matchSchematic("Castle", files), "stem match wins over prefix");
        assertEquals("house.schem", OwnerCommands.matchSchematic("hou", files), "unique prefix");
        assertNull(OwnerCommands.matchSchematic("cas", files), "ambiguous prefix");
        assertNull(OwnerCommands.matchSchematic("tower", files));
        assertEquals(0, OwnerCommands.rotationFromYaw(0));
        assertEquals(90, OwnerCommands.rotationFromYaw(80));
        assertEquals(180, OwnerCommands.rotationFromYaw(-170));
        assertEquals(270, OwnerCommands.rotationFromYaw(-90));
        assertEquals("store", OwnerCommands.trashTarget("сдать"));
        assertEquals("trash_chest", OwnerCommands.trashTarget("chest"));
        assertEquals("drop", OwnerCommands.trashTarget(null));
    }
}
