package io.github.krekerdm.baritonebots.manager.gamedata;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;

/** The small game-data tree in src/test/resources/gamedata ("fake game data" for planner tests). */
public final class Fixtures {
    private static GameData cached;

    private Fixtures() {
    }

    public static Path root() {
        try {
            return Path.of(Fixtures.class.getResource("/gamedata/data").toURI()).getParent();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    public static synchronized GameData gameData() {
        if (cached == null) {
            try {
                cached = GameDataParser.parse(root());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        return cached;
    }
}
