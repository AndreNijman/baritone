/* SPDX-License-Identifier: LGPL-3.0-or-later */
package baritone.utils;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.event.events.TickEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.pathing.goals.GoalRunAway;
import baritone.api.process.*;
import baritone.api.utils.*;
import baritone.api.utils.input.Input;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import java.util.*;
import java.util.function.Predicate;

/** Inventory-driven coordinator. Mining is delegated to the ordinary upstream mine process. */
public final class DiamondPickaxeProcess implements IBaritoneProcess, AbstractGameEventListener {
    private final IBaritone baritone;
    private final IPlayerContext ctx;
    private final Map<Settings.Setting<?>,Object> saved=new LinkedHashMap<>(), applied=new LinkedHashMap<>();
    private boolean active, mining, smelting;
    private String stage="Not started";
    private int tick, lastClick, progressTick, lastCount, retries, pendingSlot=-1;
    private Predicate<ItemStack> gathering;
    private int gatheringCount;
    private Block[] gatheringBlocks;
    private DiamondPickaxeRecipes.Recipe recipe;
    private int craftedBefore, recipeTick, placingSince;
    private final Set<BlockPos> rejectedPlacements=new HashSet<>();
    private AbstractContainerMenu ownedMenu;
    private BlockPos table, furnace, placing, pickup, relocateFrom;
    private int pickupTick, tablesBefore, tablePlacedTick, furnacePlacedTick, resyncUntil, rejections, relocations, relocateTick, tablesCrafted;
    private net.minecraft.world.level.Level world;

    public DiamondPickaxeProcess(IBaritone baritone) { this.baritone=baritone;ctx=baritone.getPlayerContext(); }
    private void log(String message) { Helper.HELPER.logDirect("Diamond pickaxe: "+message); }
    public void start() {
        if (active) { log(stage+" (already running)");return; }
        if (ctx.player()==null || ctx.world()==null) { log("Join a world first");return; }
        if (ctx.player().containerMenu!=ctx.player().inventoryMenu || !ctx.player().inventoryMenu.getCarried().isEmpty()) { log("Close the current container and clear the cursor first");return; }
        baritone.getPathingBehavior().cancelEverything();
        world=ctx.world();tick=lastClick=progressTick=0;retries=0;table=furnace=placing=pickup=relocateFrom=null;recipe=null;ownedMenu=null;rejectedPlacements.clear();resyncUntil=rejections=relocations=tablesCrafted=0;
        var s=BaritoneAPI.getSettings();
        override(s.allowBreak,true);override(s.allowPlace,true);override(s.allowInventory,false);
        // Recipe ingredients must never be used as path scaffolding.
        override(s.acceptableThrowawayItems,new ArrayList<>(List.of(Items.DIRT,Items.NETHERRACK)));
        override(s.mineScanDroppedItems,true);override(s.exploreForBlocks,true);
        override(s.blocksToAvoidBreaking,new ArrayList<>(s.blocksToAvoidBreaking.value));
        s.blocksToAvoidBreaking.value.add(Blocks.CRAFTING_TABLE);s.blocksToAvoidBreaking.value.add(Blocks.FURNACE);
        active=true;setStage("Starting from current inventory");
    }
    private <T> void override(Settings.Setting<T> setting,T value) { saved.put(setting,setting.value);setting.value=value;applied.put(setting,value); }
    @SuppressWarnings({"rawtypes","unchecked"}) private void restore() {
        saved.forEach((setting,value)->{ if(Objects.equals(setting.value,applied.get(setting))) ((Settings.Setting)setting).value=value; });
        saved.clear();applied.clear();
    }
    public void stop(String reason) {
        if (!active) return;
        active=false;mining=smelting=false;recipe=null;placing=null;pendingSlot=-1;
        baritone.getMineProcess().cancel();baritone.getInputOverrideHandler().clearAllKeys();
        closeMenu();restore();stage=reason;log(reason);
    }
    @Override public boolean isActive() { return active; }
    @Override public boolean isTemporary() { return true; }
    @Override public double priority() { return 10; }
    @Override public void onLostControl() { stop("Cancelled"); }
    @Override public String displayName0() { return "Diamond pickaxe: "+stage; }
    @Override public String displayName() { return displayName0()+(active ? "" : " (idle)"); }
    @Override public void onPlayerDeath() { stop("Stopped after death; run diamondpickaxe again after respawning"); }
    @Override public void onTick(TickEvent event) { if(active && event.getType()==TickEvent.Type.OUT) stop("Disconnected"); }
    private PathingCommand pause() { return new PathingCommand(null,PathingCommandType.REQUEST_PAUSE); }
    private void setStage(String value) { if(!stage.equals(value)) { stage=value;progressTick=tick;log(value); } }
    private int count(Predicate<ItemStack> filter) { return ctx.player().getInventory().getNonEquipmentItems().stream().filter(filter).mapToInt(ItemStack::getCount).sum(); }
    private int count(Item item) { return count(s->s.is(item)); }
    private int planks() { return count(s->s.is(ItemTags.PLANKS)); }
    private boolean tool(Item item) { return count(s->s.is(item) && (!s.isDamageableItem() || s.getMaxDamage()-s.getDamageValue()>10))>0; }

    @Override public PathingCommand onTick(boolean calcFailed,boolean safe) {
        if (!active) return null;
        if (ctx.player()==null || ctx.world()!=world || !ctx.player().isAlive()) { stop("World changed or player unavailable");return null; }
        tick++;
        if (count(Items.DIAMOND_PICKAXE)>0) { stop("Complete — diamond pickaxe is in your inventory");return null; }
        try {
            if (mining) {
                if(safe && !prepareBuildingBlocks())return pause();
                int n=count(gathering);
                if (n>=gatheringCount) {
                    mining=false;baritone.getMineProcess().cancel();progressTick=tick;
                    return new PathingCommand(null,PathingCommandType.CANCEL_AND_SET_GOAL);
                }
                if(n!=lastCount) { lastCount=n;progressTick=tick; }
                if (tick-progressTick>6000) { stop("No resource progress for five minutes; check access to "+stage);return null; }
                if (!baritone.getMineProcess().isActive()) {
                    if (++retries>3) { stop("Mining repeatedly failed during "+stage);return null; }
                    baritone.getMineProcess().mine(gatheringBlocks);
                }
                return new PathingCommand(null,PathingCommandType.DEFER);
            }
            if(!safe) return pause();
            baritone.getInputOverrideHandler().clearAllKeys();
            // After the server undid an action, give it time to resend the true inventory before counting items.
            if(tick<resyncUntil) return pause();
            if(pickup!=null) return collectTable();
            if(recipe!=null) return craft();
            if(smelting) return smelt(calcFailed);
            if(ctx.player().containerMenu!=ctx.player().inventoryMenu) { stop("A different container was opened");return null; }
            if(tick-progressTick>1800) { stop("Crafting or placement stalled during "+stage);return null; }
            return plan();
        } catch (RuntimeException error) { stop("Stopped: "+error.getClass().getSimpleName()+" — "+error.getMessage());return null; }
    }
    private PathingCommand gather(String name,Predicate<ItemStack> filter,int quantity,Block... blocks) {
        if(ctx.player().getInventory().getNonEquipmentItems().stream().noneMatch(ItemStack::isEmpty)) { stop("Inventory is full; free space and restart");return null; }
        closeMenu();if(!equipBestPick() || !prepareBuildingBlocks())return pause();
        mining=true;gathering=filter;gatheringCount=quantity;gatheringBlocks=blocks;lastCount=count(filter);retries=0;
        setStage("Gathering "+name+" ("+lastCount+"/"+quantity+")");
        baritone.getMineProcess().mine(blocks);
        return new PathingCommand(null,PathingCommandType.DEFER);
    }
    private PathingCommand wood(int needed) {
        for(ItemStack stack:ctx.player().getInventory().getNonEquipmentItems()) {
            if(stack.is(ItemTags.LOGS_THAT_BURN)) {
                var r=DiamondPickaxeRecipes.planks(stack.getItem());if(r!=null)return beginCraft(r);
            }
        }
        int logs=Math.max(1,(needed-planks()+3)/4);
        // Reserve the complete early recipe/fuel budget before leaving the first tree.
        if(!tool(Items.WOODEN_PICKAXE) && !tool(Items.STONE_PICKAXE) && !tool(Items.IRON_PICKAXE) && !tool(Items.NETHERITE_PICKAXE))logs=Math.max(logs,4);
        return gather("wood",s->s.is(ItemTags.LOGS_THAT_BURN),logs,Blocks.OAK_LOG,Blocks.SPRUCE_LOG,Blocks.BIRCH_LOG,Blocks.JUNGLE_LOG,Blocks.ACACIA_LOG,Blocks.DARK_OAK_LOG,Blocks.MANGROVE_LOG,Blocks.CHERRY_LOG,Blocks.PALE_OAK_LOG);
    }
    private PathingCommand plan() {
        // Collect separate scaffolding before entering mines, then replenish between phases.
        if(count(DiamondPickaxeProcess::buildingBlock)<4)
            return gather("disposable building blocks",s->s.is(Items.DIRT),8,Blocks.DIRT,Blocks.GRASS_BLOCK);
        if(!tool(Items.IRON_PICKAXE) && !tool(Items.DIAMOND_PICKAXE) && !tool(Items.NETHERITE_PICKAXE)) {
            if(!tool(Items.STONE_PICKAXE)) {
                if(!tool(Items.WOODEN_PICKAXE)) {
                    if(count(Items.STICK)<2) { if(planks()<2)return wood(2);return beginCraft(DiamondPickaxeRecipes.sticks()); }
                    if(planks()<3)return wood(3);
                    return beginCraft(DiamondPickaxeRecipes.pickaxe(Items.WOODEN_PICKAXE,null));
                }
                if(count(Items.COBBLESTONE)<3)return gather("cobblestone",s->s.is(Items.COBBLESTONE),3,Blocks.STONE,Blocks.COBBLESTONE);
                if(count(Items.STICK)<2) { if(planks()<2)return wood(2);return beginCraft(DiamondPickaxeRecipes.sticks()); }
                return beginCraft(DiamondPickaxeRecipes.pickaxe(Items.STONE_PICKAXE,Items.COBBLESTONE));
            }
            if(count(Items.IRON_INGOT)<3) {
                if(count(Items.RAW_IRON)+count(Items.IRON_INGOT)<3)return gather("raw iron",s->s.is(Items.RAW_IRON),3-count(Items.IRON_INGOT),Blocks.IRON_ORE,Blocks.DEEPSLATE_IRON_ORE);
                if(furnace==null && count(Items.FURNACE)==0) {
                    if(count(Items.COBBLESTONE)<8)return gather("furnace cobblestone",s->s.is(Items.COBBLESTONE),8,Blocks.STONE,Blocks.COBBLESTONE);
                    return beginCraft(DiamondPickaxeRecipes.furnace());
                }
                if(count(Items.COAL)+count(Items.CHARCOAL)==0 && planks()<3)return wood(3);
                smelting=true;recipeTick=tick;setStage("Smelting iron");return pause();
            }
            if(count(Items.STICK)<2) { if(planks()<2)return wood(2);return beginCraft(DiamondPickaxeRecipes.sticks()); }
            return beginCraft(DiamondPickaxeRecipes.pickaxe(Items.IRON_PICKAXE,Items.IRON_INGOT));
        }
        if(count(Items.DIAMOND)<3)return gather("diamonds",s->s.is(Items.DIAMOND),3,Blocks.DIAMOND_ORE,Blocks.DEEPSLATE_DIAMOND_ORE);
        if(count(Items.STICK)<2) { if(planks()<2)return wood(2);return beginCraft(DiamondPickaxeRecipes.sticks()); }
        return beginCraft(DiamondPickaxeRecipes.pickaxe(Items.DIAMOND_PICKAXE,Items.DIAMOND));
    }
    private PathingCommand beginCraft(DiamondPickaxeRecipes.Recipe value) {
        if(value.width()>2 && table==null && count(Items.CRAFTING_TABLE)==0) {
            if(planks()<4)return wood(4);
            if(++tablesCrafted>3) { stop("Crafted three crafting tables this run but they keep disappearing; the server may be undoing placements. Rejoin to resync, then restart");return null; }
            value=DiamondPickaxeRecipes.table();
        }
        recipe=value;craftedBefore=count(value.output());recipeTick=tick;pendingSlot=-1;
        setStage("Crafting "+value.output().toString());return pause();
    }
    private boolean readyToClick() { return tick-lastClick>=4; }
    private void click(AbstractContainerMenu menu,int slot,int button,ContainerInput type) {
        ctx.playerController().windowClick(menu.containerId,slot,button,type,ctx.player());lastClick=tick;
    }
    private int inventorySlot(AbstractContainerMenu menu,Predicate<ItemStack> filter) {
        for(Slot slot:menu.slots) if(slot.container==ctx.player().getInventory() && slot.getContainerSlot()<36 && filter.test(slot.getItem()))return slot.index;
        return -1;
    }
    private boolean stashCursor(AbstractContainerMenu menu) {
        ItemStack carried=menu.getCarried();if(carried.isEmpty())return true;
        int slot=inventorySlot(menu,s->s.isEmpty() || ItemStack.isSameItemSameComponents(s,carried) && s.getCount()+carried.getCount()<=s.getMaxStackSize());
        if(slot<0) { stop("No inventory space to put away crafting cursor");return false; }
        click(menu,slot,0,ContainerInput.PICKUP);return false;
    }
    private boolean transferOne(AbstractContainerMenu menu,int target,Predicate<ItemStack> match) {
        if(!readyToClick())return false;
        if(!menu.getCarried().isEmpty()) {
            if(pendingSlot==target && match.test(menu.getCarried())) { click(menu,target,1,ContainerInput.PICKUP);pendingSlot=-1; }
            else stashCursor(menu);
            return false;
        }
        int source=inventorySlot(menu,match);if(source<0)return false;
        pendingSlot=target;click(menu,source,0,ContainerInput.PICKUP);return false;
    }
    private PathingCommand craft() {
        if(count(recipe.output())>craftedBefore) {
            boolean usedTable=recipe.width()>2;
            recipe=null;pendingSlot=-1;closeMenu();progressTick=tick;
            // Carry the table to the next work site rather than crafting another one there.
            if(usedTable && table!=null) { pickup=table;pickupTick=tick;tablesBefore=count(Items.CRAFTING_TABLE); }
            return pause();
        }
        if(tick-recipeTick>1800) { stop("Crafting timed out; server may reject the recipe or inventory actions");return null; }
        if(recipe.width()>2) {
            PathingCommand station=station(Blocks.CRAFTING_TABLE,Items.CRAFTING_TABLE);if(station!=null)return station;
        }
        AbstractContainerMenu menu=ctx.player().containerMenu;
        if(!(menu instanceof AbstractCraftingMenu grid)) { stop("Expected a crafting grid");return null; }
        if(menu!=ctx.player().inventoryMenu && menu!=ownedMenu) { stop("Crafting container changed");return null; }
        ownedMenu=menu;
        if(!readyToClick())return pause();
        if(!menu.getCarried().isEmpty()) {
            if(pendingSlot>=0) {
                int i=pendingSlot-1;char ingredient=recipe.at(i,grid.getGridWidth());
                if(recipe.accepts(ingredient,menu.getCarried()) && menu.getSlot(pendingSlot).getItem().isEmpty()) { click(menu,pendingSlot,1,ContainerInput.PICKUP);pendingSlot=-1;return pause(); }
            }
            stashCursor(menu);return pause();
        }
        for(int i=0;i<grid.getInputGridSlots().size();i++) {
            Slot slot=grid.getInputGridSlots().get(i);char ingredient=recipe.at(i,grid.getGridWidth());
            if(slot.hasItem() && (ingredient==' ' || !recipe.accepts(ingredient,slot.getItem()) || slot.getItem().getCount()!=1)) { click(menu,slot.index,0,ContainerInput.QUICK_MOVE);return pause(); }
            if(ingredient!=' ' && !slot.hasItem()) { transferOne(menu,slot.index,s->recipe.accepts(ingredient,s));return pause(); }
        }
        Slot output=grid.getResultSlot();
        if(output.getItem().is(recipe.output()))click(menu,output.index,0,ContainerInput.QUICK_MOVE);
        return pause();
    }
    private PathingCommand smelt(boolean calcFailed) {
        if(count(Items.IRON_INGOT)>=3) { smelting=false;closeMenu();progressTick=tick;return pause(); }
        if(tick-recipeTick>2400) { stop("Smelting timed out; check furnace access/fuel");return null; }
        PathingCommand station=station(Blocks.FURNACE,Items.FURNACE);if(station!=null)return station;
        if(!(ctx.player().containerMenu instanceof FurnaceMenu menu))return pause();
        if(!readyToClick())return pause();
        if(!menu.getCarried().isEmpty()) {
            if(pendingSlot>=0) { click(menu,pendingSlot,1,ContainerInput.PICKUP);pendingSlot=-1; }
            else stashCursor(menu);
            return pause();
        }
        if(menu.getSlot(2).getItem().is(Items.IRON_INGOT)) { click(menu,2,0,ContainerInput.QUICK_MOVE);return pause(); }
        ItemStack input=menu.getSlot(0).getItem(),fuel=menu.getSlot(1).getItem();
        if(!input.isEmpty() && !input.is(Items.RAW_IRON)) { stop("Furnace contains a different input; leaving it intact");return null; }
        if(input.getCount()+count(Items.IRON_INGOT)<3 && count(Items.RAW_IRON)>0) { transferOne(menu,0,s->s.is(Items.RAW_IRON));return pause(); }
        if(fuel.isEmpty() && !menu.isLit()) {
            transferOne(menu,1,s->s.is(Items.COAL)||s.is(Items.CHARCOAL)||s.is(ItemTags.PLANKS));return pause();
        }
        return pause();
    }
    private PathingCommand station(Block block,Item item) {
        if((block==Blocks.CRAFTING_TABLE && ctx.player().containerMenu instanceof CraftingMenu) || (block==Blocks.FURNACE && ctx.player().containerMenu instanceof FurnaceMenu)) { ownedMenu=ctx.player().containerMenu;return null; }
        if(ctx.player().containerMenu!=ctx.player().inventoryMenu) { stop("Unexpected container; leaving it intact");return pause(); }
        BlockPos pos=block==Blocks.CRAFTING_TABLE ? table : furnace;
        if(pos!=null && ctx.world().hasChunkAt(pos) && !ctx.world().getBlockState(pos).is(block)) {
            int placed=block==Blocks.CRAFTING_TABLE ? tablePlacedTick : furnacePlacedTick;
            if(block==Blocks.CRAFTING_TABLE)table=null;else furnace=null;
            if(tick-placed<200) {
                // A station that vanishes right after placement was undone by the server (a ghost block).
                rejectedPlacements.add(pos);resyncUntil=tick+40;
                if(++rejections>=3) { stop("The server keeps removing placed stations (ghost blocks). Rejoin to resync, then restart");return pause(); }
                log("Placed station disappeared; waiting for the server to resync and trying another spot");
                return pause();
            }
            pos=null;
        }
        if(pos==null && placing!=null && ctx.world().getBlockState(placing).is(block)) {
            pos=placing;
            if(block==Blocks.CRAFTING_TABLE) { table=pos;tablePlacedTick=tick; } else { furnace=pos;furnacePlacedTick=tick; }
            placing=null;relocations=0;
        }
        if(pos==null) {
            if(count(item)==0) { recipe=null;smelting=false;return pause(); }
            if(relocateFrom!=null) {
                // Walk a few blocks to find open floor before trying again.
                if(ctx.playerFeet().distSqr(relocateFrom)>=16 || tick-relocateTick>200) relocateFrom=null;
                else return new PathingCommand(new GoalRunAway(5,relocateFrom),PathingCommandType.REVALIDATE_GOAL_AND_PATH);
            }
            if(placing==null) { placing=findPlacement();placingSince=tick; }
            if(placing==null) {
                if(++relocations>3) { stop("No reachable floor for a crafting station after moving three times");return pause(); }
                relocateFrom=ctx.playerFeet();relocateTick=tick;
                return pause();
            }
            pos=placing;
            if(ctx.world().getBlockState(pos).is(block)) { if(block==Blocks.CRAFTING_TABLE) { table=pos;tablePlacedTick=tick; } else { furnace=pos;furnacePlacedTick=tick; } placing=null;return pause(); }
            // Something (a block or entity) kept the click from landing: choose another spot.
            if(tick-placingSince>100) { rejectedPlacements.add(pos);placing=null;return pause(); }
            if(!equip(item))return pause();
            BlockPos support=pos.below();
            Rotation aim=RotationUtils.calcRotationFromVec3d(ctx.playerHead(),new Vec3(pos.getX()+0.5,pos.getY(),pos.getZ()+0.5),ctx.playerRotations());
            baritone.getLookBehavior().updateTarget(aim,true);
            if(ctx.objectMouseOver() instanceof BlockHitResult hit && hit.getType()==HitResult.Type.BLOCK && hit.getBlockPos().equals(support) && hit.getDirection()==Direction.UP && tick-lastClick>=10) {
                useBlock(hit);lastClick=tick;
            }
            return pause();
        }
        Optional<Rotation> reachable=RotationUtils.reachable(ctx,pos,ctx.playerController().getBlockReachDistance());
        if(reachable.isEmpty() && !prepareBuildingBlocks())return pause();
        if(reachable.isEmpty())return new PathingCommand(new GoalGetToBlock(pos),PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        baritone.getLookBehavior().updateTarget(reachable.get(),true);
        if(ctx.objectMouseOver() instanceof BlockHitResult hit && hit.getType()==HitResult.Type.BLOCK && hit.getBlockPos().equals(pos) && tick-lastClick>=10) {
            useBlock(hit);lastClick=tick;
        }
        return pause();
    }
    /** Break the placed table with ordinary aimed mining and walk over the drop. */
    private PathingCommand collectTable() {
        boolean collected=count(Items.CRAFTING_TABLE)>tablesBefore;
        if(collected || tick-pickupTick>400) {
            if(!collected)log("Could not pick up the crafting table; another will be crafted if needed");
            pickup=null;table=null;progressTick=tick;return pause();
        }
        if(ctx.player().containerMenu!=ctx.player().inventoryMenu) { closeMenu();return pause(); }
        if(ctx.world().getBlockState(pickup).is(Blocks.CRAFTING_TABLE)) {
            Optional<Rotation> reachable=RotationUtils.reachable(ctx,pickup,ctx.playerController().getBlockReachDistance());
            if(reachable.isEmpty())return new PathingCommand(new GoalGetToBlock(pickup),PathingCommandType.REVALIDATE_GOAL_AND_PATH);
            baritone.getLookBehavior().updateTarget(reachable.get(),true);
            if(ctx.objectMouseOver() instanceof BlockHitResult hit && hit.getType()==HitResult.Type.BLOCK && hit.getBlockPos().equals(pickup))
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT,true);
            return pause();
        }
        BlockPos drop=ctx.entitiesStream().filter(e->e instanceof net.minecraft.world.entity.item.ItemEntity item && item.getItem().is(Items.CRAFTING_TABLE) && e.distanceToSqr(Vec3.atCenterOf(pickup))<36)
                .findFirst().map(net.minecraft.world.entity.Entity::blockPosition).orElse(pickup);
        return new PathingCommand(new GoalBlock(drop),PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }
    private void useBlock(BlockHitResult hit) {
        var result=ctx.playerController().processRightClickBlock(ctx.player(),ctx.world(),InteractionHand.MAIN_HAND,hit);
        if(result instanceof net.minecraft.world.InteractionResult.Success success && success.swingSource()==net.minecraft.world.InteractionResult.SwingSource.PREDICTED)
            ctx.player().swing(InteractionHand.MAIN_HAND,ctx.player().getItemInHand(InteractionHand.MAIN_HAND).getInteractAnimation(),false);
    }
    private BlockPos findPlacement() {
        BlockPos feet=ctx.playerFeet();
        for(int radius=1;radius<=3;radius++) for(int dy:new int[]{0,-1,1}) for(int x=-radius;x<=radius;x++)for(int z=-radius;z<=radius;z++) {
            if(Math.max(Math.abs(x),Math.abs(z))!=radius)continue;
            BlockPos p=feet.offset(x,dy,z);
            // Grass, ferns and snow layers are replaced by placement like air.
            var at=ctx.world().getBlockState(p);
            if(rejectedPlacements.contains(p) || !(at.isAir() || at.canBeReplaced()) || !ctx.world().getFluidState(p).isEmpty() || !ctx.world().getBlockState(p.below()).isSolidRender() || Vec3.atCenterOf(p.below()).distanceToSqr(ctx.playerHead())>=16)continue;
            // The support's top face must be visible from the eye, or the placement click can never land.
            if(RotationUtils.reachableOffset(ctx,p.below(),new Vec3(p.getX()+0.5,p.getY(),p.getZ()+0.5),ctx.playerController().getBlockReachDistance(),false).isPresent())return p;
        }
        return null;
    }
    private static boolean buildingBlock(ItemStack stack) { return stack.is(Items.DIRT) || stack.is(Items.NETHERRACK); }
    private boolean prepareBuildingBlocks() {
        var items=ctx.player().getInventory().getNonEquipmentItems();
        for(int i=0;i<9;i++)if(buildingBlock(items.get(i)))return true;
        for(int i=9;i<items.size();i++)if(buildingBlock(items.get(i))) {
            if(readyToClick())click(ctx.player().inventoryMenu,i,8,ContainerInput.SWAP);
            return false;
        }
        return true; // Initial collection can start without scaffolding.
    }
    private boolean equip(Item item) {
        var inventory=ctx.player().getInventory();
        for(int i=0;i<inventory.getNonEquipmentItems().size();i++) if(inventory.getNonEquipmentItems().get(i).is(item)) {
            if(i>=9) { if(!readyToClick())return false;click(ctx.player().inventoryMenu,i,1,ContainerInput.SWAP);return false; }
            inventory.setSelectedSlot(i);ctx.playerController().syncHeldItem();return true;
        }
        return false;
    }
    private boolean equipBestPick() { for(Item item:List.of(Items.NETHERITE_PICKAXE,Items.DIAMOND_PICKAXE,Items.IRON_PICKAXE,Items.STONE_PICKAXE,Items.WOODEN_PICKAXE))if(tool(item))return equip(item);return true; }
    private void closeMenu() {
        if(ctx.player()!=null && ownedMenu!=null && ctx.player().containerMenu==ownedMenu)ctx.player().closeContainer();
        ownedMenu=null;pendingSlot=-1;
    }
}
