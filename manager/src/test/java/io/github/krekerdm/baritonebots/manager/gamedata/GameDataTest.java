package io.github.krekerdm.baritonebots.manager.gamedata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Parser + lookups against the small fixture tree in src/test/resources/gamedata (same layout as the client jar). */
class GameDataTest {
    @TempDir
    Path tmp;

    static Path fixtureRoot() {
        try {
            return Path.of(GameDataTest.class.getResource("/gamedata/data").toURI()).getParent();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Shared by other tests that need "fake game data". */
    static GameData fixture() {
        try {
            return GameDataParser.parse(fixtureRoot());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void tagsResolveNestedAndSurviveCycles() {
        GameData d = fixture();
        assertEquals(Set.of("minecraft:oak_log", "minecraft:oak_wood", "minecraft:spruce_log"), d.itemTag("#minecraft:logs"));
        assertEquals(Set.of("minecraft:a", "minecraft:b"), d.itemTag("loop_a"));
        assertTrue(d.blockTag("minecraft:mineable/pickaxe").contains("minecraft:deepslate_iron_ore"));
        assertTrue(d.itemTag("minecraft:missing").isEmpty());
    }

    @Test
    void craftingRecipes() {
        GameData d = fixture();
        GameData.Recipe planks = d.craftingRecipesFor("oak_planks").getFirst();
        assertEquals(GameData.SHAPELESS, planks.type());
        assertEquals(4, planks.count());
        assertEquals(List.of(List.of("minecraft:oak_log", "minecraft:oak_wood")), planks.slots());
        assertFalse(GameData.needsTable(planks));

        GameData.Recipe stick = d.craftingRecipesFor("minecraft:stick").getFirst();
        assertEquals(1, stick.width());
        assertEquals(2, stick.height());
        List<List<List<String>>> grid = GameData.craftingGrid(stick);
        assertEquals(List.of("minecraft:oak_planks", "minecraft:spruce_planks"), grid.get(0).get(0));
        assertEquals(List.of("minecraft:oak_planks", "minecraft:spruce_planks"), grid.get(1).get(0));
        assertNull(grid.get(0).get(1));
        assertNull(grid.get(2).get(0));

        assertFalse(GameData.needsTable(d.craftingRecipesFor("crafting_table").getFirst()));
        GameData.Recipe furnace = d.craftingRecipesFor("furnace").getFirst();
        assertTrue(GameData.needsTable(furnace));
        assertNull(GameData.craftingGrid(furnace).get(1).get(1), "the furnace pattern has a hole");
        assertEquals(8, furnace.ingredients().size());

        GameData.Recipe torch = d.craftingRecipesFor("torch").getFirst();
        assertEquals(List.of("minecraft:coal", "minecraft:charcoal"), torch.slots().get(0));
        assertEquals("minecraft:white_wool", d.craftingRecipesFor("white_wool").getFirst().result(), "legacy item objects");
        assertTrue(GameData.needsTable(d.craftingRecipesFor("firework_rocket").getFirst()), "5 shapeless ingredients");
        assertEquals(List.of(), d.recipesFor("minecraft:nothing"), "empty tag = not craftable");
    }

    @Test
    void cookingAndOrdering() {
        GameData d = fixture();
        List<GameData.Recipe> iron = d.smeltingSources("iron_ingot");
        assertEquals(List.of(GameData.SMELTING, GameData.BLASTING), iron.stream().map(GameData.Recipe::type).toList());
        assertEquals(List.of("minecraft:sand", "minecraft:red_sand"), d.smeltingSources("glass").getFirst().slots().getFirst());
        List<GameData.Recipe> bricks = d.recipesFor("stone_bricks");
        assertEquals(GameData.SHAPED, bricks.get(0).type(), "crafting first");
        assertEquals(GameData.STONECUTTING, bricks.get(1).type());
        assertEquals(1, d.smeltingSources("cooked_beef").size());
    }

    @Test
    void lootWithoutSilkTouch() {
        GameData d = fixture();
        assertEquals(List.of("minecraft:cobblestone", "minecraft:stone"), d.blocksDropping("cobblestone"));
        assertEquals(List.of("minecraft:deepslate_iron_ore", "minecraft:iron_ore"), d.blocksDropping("raw_iron"));
        assertEquals(List.of(), d.blocksDropping("glass"), "glass only drops with silk touch");
        assertEquals(List.of(), d.blocksDropping("oak_leaves"), "leaves need shears or silk touch");
        assertEquals(List.of("minecraft:oak_leaves"), d.blocksDropping("oak_sapling"));
        assertEquals(List.of("minecraft:dirt", "minecraft:grass_block"), d.blocksDropping("dirt"));
        List<GameData.Drop> gravel = d.drops("gravel");
        assertEquals(2, gravel.size());
        assertTrue(gravel.stream().noneMatch(GameData.Drop::guaranteed), "flint is a chance, gravel only when flint fails");
        assertTrue(d.drops("stone").getFirst().guaranteed());
        assertEquals("minecraft:cobblestone", d.drops("minecraft:stone").getFirst().item());
    }

    @Test
    void toolRequirements() {
        GameData d = fixture();
        assertEquals(new GameData.ToolReq("pickaxe", "wood", true), d.toolFor("stone"));
        assertEquals(new GameData.ToolReq("pickaxe", "stone", true), d.toolFor("minecraft:iron_ore"));
        assertEquals(new GameData.ToolReq("pickaxe", "iron", true), d.toolFor("diamond_ore"));
        assertEquals(new GameData.ToolReq("axe", "wood", false), d.toolFor("oak_log[axis=y]"));
        assertEquals("shovel", d.toolFor("sand").kind());
        assertEquals(GameData.ToolReq.NONE, d.toolFor("minecraft:glass"));
    }

    @Test
    void parsesAJarAndFindsCandidates() throws IOException {
        Path versions = tmp.resolve("mc/versions");
        Path jar = versions.resolve("fabric-loader-0.19.5-26.2/fabric-loader-0.19.5-26.2.jar");
        Files.createDirectories(jar.getParent());
        Files.createDirectories(versions.resolve("26.2")); // HeadlessMC keeps only 26.2.json here
        Path root = fixtureRoot();
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar));
             Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                zip.putNextEntry(new ZipEntry(root.relativize(f).toString().replace('\\', '/')));
                Files.copy(f, (OutputStream) zip);
                zip.closeEntry();
            }
        }
        assertEquals(List.of(jar), GameDataService.candidates(tmp.resolve("mc"), "26.2", "0.19.5", null));
        Path custom = tmp.resolve("custom.jar");
        Files.copy(jar, custom);
        assertEquals(custom, GameDataService.candidates(tmp.resolve("mc"), "26.2", "0.19.5", custom.toString()).getFirst());
        GameData fromJar = GameDataParser.parse(jar);
        assertEquals(fixture().stats().get("recipes"), fromJar.stats().get("recipes"));
        assertEquals(fixture().blocksDropping("raw_iron"), fromJar.blocksDropping("raw_iron"));
        assertTrue(GameDataService.candidates(tmp.resolve("nope"), "26.2", "0.19.5", null).isEmpty());
    }
}
