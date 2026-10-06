/* SPDX-License-Identifier: LGPL-3.0-or-later */
package baritone.utils;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Small vanilla recipe set; uses manual grids rather than unlocked recipe-book entries. */
public final class DiamondPickaxeRecipes {
    private DiamondPickaxeRecipes() {}
    public record Recipe(Item output, int amount, int width, int height, String pattern, Item material) {
        public char at(int slot, int gridWidth) {
            int x=slot%gridWidth, y=slot/gridWidth;
            return x<width && y<height ? pattern.charAt(y*width+x) : ' ';
        }
        public boolean accepts(char ingredient, ItemStack stack) {
            if (stack.isEmpty()) return false;
            return switch (ingredient) {
                case 'P' -> stack.is(ItemTags.PLANKS);
                case 'S' -> stack.is(Items.STICK);
                case 'M' -> stack.is(material);
                default -> false;
            };
        }
    }
    public static Recipe table() { return new Recipe(Items.CRAFTING_TABLE,1,2,2,"PPPP",null); }
    public static Recipe sticks() { return new Recipe(Items.STICK,4,1,2,"PP",null); }
    public static Recipe furnace() { return new Recipe(Items.FURNACE,1,3,3,"MMMM MMMM",Items.COBBLESTONE); }
    public static Recipe pickaxe(Item output, Item material) { return new Recipe(output,1,3,3,material==null ? "PPP S  S " : "MMM S  S ",material); }
    public static Recipe planks(Item log) {
        String name=BuiltInRegistries.ITEM.getKey(log).getPath();
        if (!name.endsWith("_log")) return null;
        String planks=name.replace("stripped_","").replace("_log","_planks");
        Item output=BuiltInRegistries.ITEM.get(net.minecraft.resources.Identifier.withDefaultNamespace(planks)).orElseThrow().value();
        return new Recipe(output,4,1,1,"M",log);
    }
}
