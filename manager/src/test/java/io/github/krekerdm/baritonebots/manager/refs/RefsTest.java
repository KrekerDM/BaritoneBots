package io.github.krekerdm.baritonebots.manager.refs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.geom.Box;
import io.github.krekerdm.baritonebots.common.geom.Pos;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.manager.tasks.TaskCatalog;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RefsTest {
    private static final String OW = "minecraft:overworld";

    /** A fixed world: home, one waypoint in the nether, an area, a bot, the owner, an auto value. */
    private static final class Fake implements Refs.Context {
        Refs.Owner owner = new Refs.Owner(new Pos(10, 70, 10), new Pos(12, 69, 10), "minecraft:chest", OW, 0);
        String dim = OW;
        final Map<String, Object> autos = new HashMap<>();

        @Override
        public Refs.Located home() {
            return new Refs.Located(new Pos(0, 64, 0), OW);
        }

        @Override
        public Refs.Located waypoint(String name) {
            return switch (name) {
                case "mine" -> new Refs.Located(new Pos(50, 12, -40), OW);
                case "portal" -> new Refs.Located(new Pos(5, 70, 5), "minecraft:the_nether");
                default -> null;
            };
        }

        @Override
        public Refs.Located bot(String id) {
            return "bot2".equals(id) ? new Refs.Located(new Pos(-7, 63, 9), OW) : null;
        }

        @Override
        public Refs.AreaBox area(String name) {
            return "field".equals(name) ? new Refs.AreaBox(new Box(new Pos(0, 60, 0), new Pos(9, 62, 9)), OW) : null;
        }

        @Override
        public Refs.Owner owner() {
            return owner;
        }

        @Override
        public Object auto(String arg, String type) {
            return autos.get(arg);
        }

        @Override
        public String dim() {
            return dim;
        }
    }

    private static Map<String, String> types(Object... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], (String) kv[i + 1]);
        }
        return m;
    }

    @Test
    void positionReferencesBecomeCoordinates() {
        Fake ctx = new Fake();
        JsonObject args = Json.obj("center", Json.obj("ref", "home"), "radius", 12);
        JsonObject out = Refs.resolve(args, types("center", "pos"), ctx);
        assertEquals(new Pos(0, 64, 0), Pos.fromJson(out.get("center")));
        assertEquals(12, out.get("radius").getAsInt(), "other arguments stay");
        assertEquals(new Pos(50, 12, -40), Refs.pos(Json.obj("ref", "waypoint", "name", "mine"), "a", "pos", ctx));
        assertEquals(new Pos(10, 70, 10), Refs.pos(Json.obj("ref", "owner"), "a", "pos", ctx));
        assertEquals(new Pos(12, 69, 10), Refs.pos(Json.obj("ref", "owner_look"), "a", "pos", ctx));
        assertEquals(new Pos(-7, 63, 9), Refs.pos(Json.obj("ref", "bot", "id", "bot2"), "a", "pos", ctx));
        assertEquals(new Pos(1, 2, 3), Refs.pos(Json.obj("x", 1, "y", 2, "z", 3), "a", "pos", ctx), "plain positions pass");
    }

    @Test
    void boxesFromAreasAndTwoReferences() {
        Fake ctx = new Fake();
        assertEquals(new Box(new Pos(0, 60, 0), new Pos(9, 62, 9)), Refs.box(Json.obj("ref", "area", "name", "field"), "box", ctx));
        Box two = Refs.box(Json.arr(Json.obj("ref", "home"), Json.obj("ref", "owner_look")), "box", ctx);
        assertEquals(new Box(new Pos(0, 64, 0), new Pos(12, 69, 10)), two);
        Box ab = Refs.box(Json.obj("a", Json.obj("ref", "owner"), "b", Json.obj("x", 0, "y", 0, "z", 0)), "box", ctx);
        assertEquals(new Box(new Pos(0, 0, 0), new Pos(10, 70, 10)), ab);
        ctx.autos.put("box", new Box(new Pos(1, 1, 1), new Pos(2, 2, 2)));
        assertEquals(new Box(new Pos(1, 1, 1), new Pos(2, 2, 2)), Refs.box(Json.obj("ref", "auto"), "box", ctx));
    }

    @Test
    void containerListsExpandAuto() {
        Fake ctx = new Fake();
        ctx.autos.put("containers", List.of(new Pos(1, 64, 1), new Pos(2, 64, 2)));
        var out = Refs.containers(Json.arr(Json.obj("ref", "owner_look"), Json.obj("ref", "auto"),
                Json.obj("x", 1, "y", 64, "z", 1)), "containers", ctx);
        assertEquals(3, out.size(), "auto expands; a container listed twice is kept once");
        assertEquals(new Pos(12, 69, 10), Pos.fromJson(out.get(0)));
        assertEquals(2, Refs.containers(Json.obj("ref", "auto"), "containers", ctx).size(), "auto alone is a list");
    }

    @Test
    void unresolvableReferencesFailWithAReason() {
        Fake ctx = new Fake();
        assertEquals("ref_no_waypoint", assertThrows(Refs.RefException.class,
                () -> Refs.pos(Json.obj("ref", "waypoint", "name", "x"), "a", "pos", ctx)).reason());
        assertEquals("ref_wrong_dim", assertThrows(Refs.RefException.class,
                () -> Refs.pos(Json.obj("ref", "waypoint", "name", "portal"), "a", "pos", ctx)).reason());
        assertEquals("ref_no_bot", assertThrows(Refs.RefException.class,
                () -> Refs.pos(Json.obj("ref", "bot", "id", "ghost"), "a", "pos", ctx)).reason());
        assertEquals("ref_auto_none", assertThrows(Refs.RefException.class,
                () -> Refs.pos(Json.obj("ref", "auto"), "a", "pos", ctx)).reason());
        assertEquals("bad_ref", assertThrows(Refs.RefException.class,
                () -> Refs.box(Json.obj("ref", "home"), "box", ctx)).reason(), "a single point is not a box");
        Fake noLook = new Fake();
        noLook.owner = new Refs.Owner(new Pos(1, 1, 1), null, null, OW, 0);
        assertEquals("ref_no_look", assertThrows(Refs.RefException.class,
                () -> Refs.pos(Json.obj("ref", "owner_look"), "a", "pos", noLook)).reason());
        noLook.owner = null;
        assertEquals("ref_no_owner", assertThrows(Refs.RefException.class,
                () -> Refs.pos(Json.obj("ref", "owner"), "a", "pos", noLook)).reason());
        Fake anyDim = new Fake();
        anyDim.dim = null;
        assertEquals(new Pos(5, 70, 5), Refs.pos(Json.obj("ref", "waypoint", "name", "portal"), "a", "pos", anyDim),
                "no dimension check when the caller has none");
    }

    @Test
    void detectionAndGotoHelpers() {
        JsonObject args = Json.obj("box", Json.arr(Json.obj("ref", "owner"), Json.obj("ref", "auto")),
                "containers", Json.arr(Json.obj("x", 1, "y", 2, "z", 3)), "note", Json.obj("ref", "home"));
        Map<String, String> t = types("box", "box", "containers", "containers");
        assertEquals(Set.of("owner", "auto"), Refs.kinds(args, t), "only reference-capable arguments count");
        assertEquals(List.of("box"), Refs.autoArgs(args, t));
        assertTrue(Refs.contains(args));
        assertFalse(Refs.contains(Json.obj("x", 1)));
        JsonObject g = Refs.gotoCoordinates(Json.obj("pos", Json.obj("x", 4, "y", 5, "z", 6), "range", 1));
        assertEquals(List.of(4, 5, 6, 1), List.of(g.get("x").getAsInt(), g.get("y").getAsInt(), g.get("z").getAsInt(),
                g.get("range").getAsInt()));
        assertFalse(g.has("pos"));
    }

    @Test
    void catalogMarksReferenceArguments() {
        TaskCatalog c = TaskCatalog.load();
        assertEquals("pos", c.refArgs("goto").get("pos"));
        assertEquals("box", c.refArgs("breed").get("box"));
        assertEquals("containers", c.refArgs("deposit").get("containers"));
        assertEquals("container", c.refArgs("take").get("container"));
        assertEquals("pos", c.projectRefArgs("build").get("origin"));
        assertEquals("box", c.projectRefArgs("ranch").get("box"));
        assertTrue(c.refArgs("mine").isEmpty());
        // goto needs x + z or a pos reference
        assertThrows(TaskCatalog.BadArgsException.class, () -> c.normalize("goto", Json.obj("x", 1)));
        assertEquals(1, c.normalize("goto", Json.obj("pos", Json.obj("ref", "home"))).size());
    }
}
