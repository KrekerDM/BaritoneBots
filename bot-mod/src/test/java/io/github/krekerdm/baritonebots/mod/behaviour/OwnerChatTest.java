package io.github.krekerdm.baritonebots.mod.behaviour;

import io.github.krekerdm.baritonebots.common.msg.BotConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OwnerChatTest {
    private static OwnerChat chat(String owner) {
        BotConfig.Owner d = BotConfig.Owner.defaults();
        return new OwnerChat(new BotConfig.Owner(owner, d.prefix(), d.patterns()));
    }

    @Test
    void signedChatUsesTheSender() {
        OwnerChat c = chat("Krekerdm");
        OwnerChat.Command cmd = c.fromPlayer("krekerDM", "!b build castle.schem 90", false);
        assertEquals("build castle.schem 90", cmd.text());
        assertEquals(OwnerChat.VIA_CHAT, cmd.via());
        assertEquals(OwnerChat.VIA_WHISPER, c.fromPlayer("Krekerdm", "!B come", true).via());
        assertNull(c.fromPlayer("Someone", "!b come", false), "other players are ignored");
        assertNull(c.fromPlayer("Krekerdm", "hello !b come", false), "the prefix must start the message");
        assertNull(c.fromPlayer("Krekerdm", "!bcome", false), "the prefix is a word of its own");
        assertEquals("", c.fromPlayer("Krekerdm", "!b", false).text(), "bare prefix = help");
    }

    @Test
    void systemLinesInVanillaAndPluginFormats() {
        OwnerChat c = chat("Krekerdm");
        // vanilla public chat rendered as text
        assertEquals("come", c.fromSystem("<Krekerdm> !b come").text());
        // vanilla whispers, English and Russian client language
        OwnerChat.Command en = c.fromSystem("Krekerdm whispers to you: !b follow");
        assertEquals("follow", en.text());
        assertEquals(OwnerChat.VIA_WHISPER, en.via());
        OwnerChat.Command ru = c.fromSystem("Krekerdm шепчет вам: !b домой");
        assertEquals("домой", ru.text());
        assertEquals(OwnerChat.VIA_WHISPER, ru.via());
        // Essentials private message, English and Russian
        assertEquals(OwnerChat.VIA_WHISPER, c.fromSystem("[Krekerdm -> me] !b stop").via());
        assertEquals("стоп", c.fromSystem("[Krekerdm -> я] !b стоп").text());
        // chat plugins with rank tags
        OwnerChat.Command plugin = c.fromSystem("[G] [Админ] Krekerdm: !b obtain iron_ingot 32");
        assertEquals("obtain iron_ingot 32", plugin.text());
        assertEquals(OwnerChat.VIA_SYSTEM, plugin.via());
        assertEquals("farm", c.fromSystem("Krekerdm » !b farm").text());
    }

    @Test
    void spoofedLinesAreRejected() {
        OwnerChat c = chat("Krekerdm");
        assertNull(c.fromSystem("<Mallory> Krekerdm: !b stop"), "the first matching pattern decides the speaker");
        assertNull(c.fromSystem("[G] Mallory: Krekerdm: !b stop"));
        assertNull(c.fromSystem("Server restarting in 5 minutes"));
        OwnerChat none = chat("");
        assertTrue(none.ownerUnknown());
        assertEquals("Steve", none.fromSystem("<Steve> !b come").player(),
                "no owner configured: any player's command is passed on as an owner candidate");
        assertEquals("come", none.fromPlayer("Alex", "!b come", false).text());
        assertNull(none.fromPlayer("Alex", "hello", false), "still only lines with the prefix");
        assertFalse(chat("Krekerdm").ownerUnknown());
    }

    @Test
    void customPrefixAndBadPatterns() {
        OwnerChat c = new OwnerChat(new BotConfig.Owner("Boss", "#bot", List.of("^(?<name>\\w+): (?<msg>.*)$",
                "([unclosed", "^no groups$")));
        assertEquals("home", c.fromSystem("Boss: #bot home").text());
        assertNull(c.fromSystem("Boss: !b home"));
        assertEquals(2, c.invalidPatterns().size());
        assertTrue(c.enabled());
    }
}
