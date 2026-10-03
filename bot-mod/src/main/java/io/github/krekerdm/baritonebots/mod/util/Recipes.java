package io.github.krekerdm.baritonebots.mod.util;

import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Crafting recipes the client recipe book knows (what the server unlocked for the bot). Only shaped and shapeless
 * crafting displays are considered; results are resolved with {@link SlotDisplayContext#fromLevel}.
 */
public final class Recipes {
    /** One crafting recipe for an item: grid footprint and items per craft. */
    public record Option(RecipeDisplayEntry entry, int width, int height, int ingredients, boolean shapeless,
                         int perCraft) {
        public boolean fits(int gridWidth, int gridHeight) {
            return shapeless ? ingredients <= gridWidth * gridHeight : width <= gridWidth && height <= gridHeight;
        }

        /** Needs a 3×3 crafting table (does not fit the 2×2 inventory grid). */
        public boolean needsTable() {
            return !fits(2, 2);
        }

        public int displayId() {
            return entry.id().index();
        }

        /** {@code true}/{@code false} from the client's own material check, {@code null} when it cannot tell. */
        public Boolean craftable(StackedItemContents contents) {
            if (entry.craftingRequirements().isEmpty()) {
                return null;
            }
            return entry.canCraft(contents);
        }
    }

    private Recipes() {
    }

    /** Known crafting recipes whose (first) result is {@code itemId}. */
    public static List<Option> forItem(LocalPlayer p, Level level, String itemId) {
        List<Option> out = new ArrayList<>();
        if (p == null || level == null || itemId == null) {
            return out;
        }
        ContextMap ctx = SlotDisplayContext.fromLevel(level);
        for (RecipeDisplayEntry e : known(p.getRecipeBook())) {
            ItemStack result = firstResult(e, ctx);
            Option o = itemId.equals(McIds.item(result)) ? option(e, result) : null;
            if (o != null) {
                out.add(o);
            }
        }
        return out;
    }

    /** What the player's inventory holds, for {@link Option#craftable}. */
    public static StackedItemContents inventoryContents(LocalPlayer p) {
        StackedItemContents c = new StackedItemContents();
        p.getInventory().fillStackedContents(c);
        return c;
    }

    private static Option option(RecipeDisplayEntry e, ItemStack first) {
        RecipeDisplay d = e.display();
        int w;
        int h;
        int n;
        boolean shapeless;
        if (d instanceof ShapedCraftingRecipeDisplay s) {
            w = s.width();
            h = s.height();
            n = s.ingredients().size();
            shapeless = false;
        } else if (d instanceof ShapelessCraftingRecipeDisplay s) {
            n = s.ingredients().size();
            w = Math.min(3, n);
            h = (n + 2) / 3;
            shapeless = true;
        } else {
            return null;
        }
        return first.isEmpty() ? null : new Option(e, w, h, n, shapeless, Math.max(1, first.getCount()));
    }


    private static ItemStack firstResult(RecipeDisplayEntry e, ContextMap ctx) {
        try {
            List<ItemStack> r = e.resultItems(ctx);
            return r.isEmpty() ? ItemStack.EMPTY : r.getFirst();
        } catch (RuntimeException ex) {
            return ItemStack.EMPTY;
        }
    }

    /** Every entry of the book: the private {@code known} map, else the (rebuilt) collections. */
    private static Collection<RecipeDisplayEntry> known(ClientRecipeBook book) {
        List<RecipeDisplayEntry> out = new ArrayList<>();
        if (book == null) {
            return out;
        }
        if (Reflect.get(book, "known") instanceof Map<?, ?> map) {
            for (Object v : map.values()) {
                if (v instanceof RecipeDisplayEntry e) {
                    out.add(e);
                }
            }
            return out;
        }
        for (RecipeCollection c : book.getCollections()) {
            out.addAll(c.getRecipes());
        }
        return out;
    }
}
