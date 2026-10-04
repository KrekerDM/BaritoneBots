package io.github.krekerdm.baritonebots.manager.autopilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.manager.config.ManagerConfig;
import io.github.krekerdm.baritonebots.manager.refs.Refs;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The attention hold after owner come / follow / goto-to-owner (live test: the bot came, then walked off to inspect). */
class AttentionHoldTest {
    private static final long NOW = 1_000_000L;

    private static long until(String type, String origin, Set<String> kinds, int sec) {
        return AttentionHold.untilFor(type, origin, Json.obj("player", "Owner"), kinds, "Owner", "owner", NOW, sec);
    }

    @Test
    void ownerCallsStartAHoldOtherManualWorkReleasesIt() {
        assertEquals(NOW + 300_000, until("goto", "owner", Set.of(), 300), "owner come");
        assertEquals(AttentionHold.WHILE_FOLLOWING, until("follow", "owner", Set.of(), 300), "owner follow");
        assertEquals(AttentionHold.WHILE_FOLLOWING, until("follow", "panel", Set.of(), 300), "follow the owner by hand");
        assertEquals(NOW + 300_000, until("goto", "panel", Set.of(Refs.OWNER_LOOK), 300), "goto to owner_look");
        assertEquals(NOW + 300_000, until("goto", "panel", Set.of(Refs.OWNER), 300), "goto to the owner");
        assertEquals(0, until("goto", "panel", Set.of(Refs.HOME), 300), "a plain goto releases");
        assertEquals(0, until("trash", "owner", Set.of(), 300), "the next owner command releases");
        assertEquals(0, until("goto", "owner", Set.of(), 0), "0 = off");
        assertEquals(0, AttentionHold.untilFor("follow", "panel", Json.obj("player", "Steve"), Set.of(), "Owner",
                "owner", NOW, 300), "following someone else is no owner call");
    }

    @Test
    void holdExpiresFollowHoldsWhileFollowing() {
        AttentionHold h = new AttentionHold();
        assertFalse(h.held("bot1", false, NOW, 300));
        h.set("bot1", NOW + 300_000);
        assertTrue(h.held("bot1", false, NOW + 5_000, 300), "5 s after come the bot stays with the owner");
        assertFalse(h.held("bot1", false, NOW + 300_000, 300), "expired");
        assertFalse(h.held("bot1", false, NOW + 1, 300), "and forgotten");

        h.set("bot1", AttentionHold.WHILE_FOLLOWING);
        assertTrue(h.held("bot1", true, NOW + 86_400_000, 300), "follow holds until stopped");
        assertTrue(h.held("bot1", false, NOW, 300), "follow ended on its own: the normal hold starts");
        assertFalse(h.held("bot1", false, NOW + 300_000, 300));

        h.set("bot1", NOW + 300_000);
        h.set("bot1", 0);
        assertFalse(h.held("bot1", false, NOW, 300), "a manual task releases");
        h.set("bot1", NOW + 300_000);
        h.release("bot1");
        assertFalse(h.held("bot1", false, NOW, 300), "stop releases");
    }

    @Test
    void settingDefaults() {
        assertEquals(300, ManagerConfig.AutopilotCfg.defaults().holdAfterOwnerSec());
        assertEquals(48, ManagerConfig.AutopilotCfg.defaults().inspectMaxDistance());
    }
}
