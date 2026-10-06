/* SPDX-License-Identifier: LGPL-3.0-or-later */
package baritone.utils;

import baritone.api.IBaritone;
import baritone.api.event.events.TickEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.Helper;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.cow.AbstractCow;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.animal.rabbit.Rabbit;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.util.Comparator;
import java.util.List;

/**
 * Temporary process between mob safety and ordinary tasks: eats when hungry during any Baritone task, and while
 * #diamondpickaxe runs, hunts nearby farm animals for meat when there is no food.
 */
public final class AutoFood implements IBaritoneProcess, AbstractGameEventListener {
    private static final int IDLE = 0, EATING = 1, HUNTING = 2;
    private static volatile boolean enabled = true;
    private static boolean preferenceLoaded;
    private final IBaritone baritone;
    private final IPlayerContext ctx;
    private int mode, ticks, lastSwap, huntCooldown, clock;
    private boolean automation, holdingUse;
    private Entity prey;
    private BlockPos preyGoal;
    private String state = "idle";

    public AutoFood(IBaritone baritone) { this.baritone = baritone; ctx = baritone.getPlayerContext(); }

    public static boolean enabled() { return enabled; }
    public static void enable(boolean value) { enabled = value; }
    public String state() { return state; }
    private void setState(String value) { if (!state.equals(value)) { state = value; Helper.HELPER.logDirect("Food: " + value); } }

    private int food() { return ctx.player().getFoodData().getFoodLevel(); }
    private boolean hungry() { return food() <= 14 || ctx.player().getHealth() < 14 && food() <= 17; }

    /** Nutrition of a stack worth eating now, or 0. Harmful, teleporting or precious foods are never chosen. */
    private int value(ItemStack stack) {
        var props = stack.get(DataComponents.FOOD);
        if (props == null || stack.is(Items.SPIDER_EYE) || stack.is(Items.PUFFERFISH) || stack.is(Items.POISONOUS_POTATO) || stack.is(Items.CHORUS_FRUIT)
                || stack.is(Items.SUSPICIOUS_STEW) || stack.is(Items.GOLDEN_APPLE) || stack.is(Items.ENCHANTED_GOLDEN_APPLE)) return 0;
        // These cause hunger; only when starving.
        if ((stack.is(Items.ROTTEN_FLESH) || stack.is(Items.CHICKEN)) && food() > 6) return 0;
        return props.nutrition();
    }
    private int bestFoodSlot() {
        var items = ctx.player().getInventory().getNonEquipmentItems();
        int best = -1, bestValue = 0;
        for (int i = 0; i < items.size(); i++) { int v = value(items.get(i)); if (v > bestValue) { bestValue = v; best = i; } }
        return best;
    }
    private int foodItems() {
        int n = 0;
        for (ItemStack stack : ctx.player().getInventory().getNonEquipmentItems()) if (stack.get(DataComponents.FOOD) != null && !stack.is(Items.ROTTEN_FLESH) && !stack.is(Items.SPIDER_EYE)) n += stack.getCount();
        return n;
    }
    private static boolean edibleAnimal(Entity entity) {
        return (entity instanceof AbstractCow || entity instanceof Pig || entity instanceof Sheep || entity instanceof Chicken || entity instanceof Rabbit)
                && entity.isAlive() && !((LivingEntity) entity).isBaby();
    }
    private Entity nearestPrey() {
        var player = ctx.player();
        return ctx.entitiesStream().filter(AutoFood::edibleAnimal).filter(e -> e.distanceTo(player) < 48 && Math.abs(e.getY() - player.getY()) < 8)
                .min(Comparator.comparingDouble(e -> e.distanceTo(player))).orElse(null);
    }
    private boolean idleScreen() { return ctx.player().containerMenu == ctx.player().inventoryMenu && ctx.minecraft().gui.screen() == null; }

    @Override public boolean isActive() {
        if (!enabled || ctx.player() == null || ctx.world() == null || !ctx.player().isAlive()) { release(); mode = IDLE; return false; }
        if (mode != IDLE) return true;
        if (!automation || !idleScreen() || !hungry()) return false;
        if (bestFoodSlot() >= 0) return true;
        return DiamondPickaxeProcess.running() && huntCooldown == 0 && nearestPrey() != null;
    }

    @Override public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (mode == IDLE) { mode = bestFoodSlot() >= 0 ? EATING : HUNTING; ticks = 0; prey = null; preyGoal = null; }
        ticks++;
        return mode == EATING ? eat() : hunt(calcFailed);
    }

    /** Keep eating until hunger allows natural regeneration (to full when hurt). */
    private boolean wantsMore() { return food() < (ctx.player().getHealth() < 14 ? 20 : 18); }

    private PathingCommand eat() {
        int slot = bestFoodSlot();
        if (slot < 0 || !wantsMore()) { release(); mode = IDLE; setState(slot < 0 && hungry() ? "out of food" : "fed"); return new PathingCommand(null, PathingCommandType.DEFER); }
        if (ticks > 400) { release(); mode = IDLE; huntCooldown = 600; setState("could not eat; trying again later"); return new PathingCommand(null, PathingCommandType.DEFER); }
        var inventory = ctx.player().getInventory();
        if (slot >= 9) {
            // Bring food into the hotbar's last slot with an ordinary swap click.
            release();
            if (clock - lastSwap >= 4) { ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId, slot, 8, ContainerInput.SWAP, ctx.player()); lastSwap = clock; }
            return pause();
        }
        if (inventory.getSelectedSlot() != slot) { release(); inventory.setSelectedSlot(slot); ctx.playerController().syncHeldItem(); return pause(); }
        // Look at the floor so the use click cannot open a table, furnace or door.
        BlockPos floor = ctx.playerFeet().below();
        Rotation down = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), Vec3.atCenterOf(floor), ctx.playerRotations());
        baritone.getLookBehavior().updateTarget(down, true);
        boolean menuBlock = ctx.objectMouseOver() instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
                && ctx.world().getBlockState(hit.getBlockPos()).getMenuProvider(ctx.world(), hit.getBlockPos()) != null;
        if (menuBlock || ctx.playerRotations().getPitch() < 45) { release(); return pause(); }
        setState("eating " + inventory.getNonEquipmentItems().get(slot).getHoverName().getString());
        ctx.minecraft().options.keyUse.setDown(true);
        holdingUse = true;
        return pause();
    }

    private PathingCommand hunt(boolean calcFailed) {
        if (bestFoodSlot() >= 0 && foodItems() >= 4 || ticks > 1200 || !DiamondPickaxeProcess.running()) {
            mode = IDLE; huntCooldown = bestFoodSlot() >= 0 ? 0 : 1200; preyGoal = null;
            setState(bestFoodSlot() >= 0 ? "collected food" : "found no food; trying again in a minute");
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        // Collect meat dropped nearby first.
        var player = ctx.player();
        ItemEntity drop = ctx.entitiesStream().filter(e -> e instanceof ItemEntity item && item.getItem().get(DataComponents.FOOD) != null && e.distanceTo(player) < 10)
                .map(e -> (ItemEntity) e).min(Comparator.comparingDouble(e -> e.distanceTo(player))).orElse(null);
        if (drop != null && (prey == null || !prey.isAlive())) { setState("collecting food"); return new PathingCommand(new GoalBlock(drop.blockPosition()), PathingCommandType.REVALIDATE_GOAL_AND_PATH); }
        if (prey == null || !prey.isAlive() || calcFailed) prey = nearestPrey();
        if (prey == null) { ticks = Math.max(ticks, 1100); return pause(); }
        setState("hunting " + prey.getName().getString());
        double distance = player.distanceTo(prey);
        if (distance > 2.8 || !player.hasLineOfSight(prey)) {
            if (preyGoal == null || preyGoal.distSqr(prey.blockPosition()) > 4) preyGoal = prey.blockPosition();
            return new PathingCommand(new GoalNear(preyGoal, 1), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        }
        preyGoal = null;
        equipWeapon();
        Rotation aim = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), prey.getBoundingBox().getCenter(), ctx.playerRotations());
        baritone.getLookBehavior().updateTarget(aim, true);
        // Swing only with the crosshair on the animal and a full attack charge, like a player would.
        if (ctx.minecraft().crosshairPickEntity == prey && player.getAttackStrengthScale(0.5f) >= 0.95f) {
            // Same sequence as Minecraft.startAttack: attack, swing with the held item's animation, Punch.
            ctx.minecraft().gameMode.attack(player, prey);
            player.swing(InteractionHand.MAIN_HAND, player.getMainHandItem().getAttackAnimation(), false);
            player.connection.send(net.minecraft.network.protocol.game.ServerboundPunchPacket.INSTANCE);
        }
        return pause();
    }

    private void equipWeapon() {
        var inventory = ctx.player().getInventory();
        for (var tag : List.of(ItemTags.SWORDS, ItemTags.AXES, ItemTags.PICKAXES)) for (int i = 0; i < 9; i++) {
            if (inventory.getNonEquipmentItems().get(i).is(tag)) { if (inventory.getSelectedSlot() != i) { inventory.setSelectedSlot(i); ctx.playerController().syncHeldItem(); } return; }
        }
    }

    private PathingCommand pause() { return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE); }
    private void release() { if (holdingUse) { ctx.minecraft().options.keyUse.setDown(false); holdingUse = false; } }

    @Override public void onTick(TickEvent event) {
        if (event.getType() == TickEvent.Type.OUT) { release(); mode = IDLE; automation = false; return; }
        clock++;
        if (huntCooldown > 0) huntCooldown--;
        var inControl = baritone.getPathingControlManager().mostRecentInControl();
        automation = inControl.filter(process -> process != this).isPresent();
        // A higher-priority process (mob safety) took over without telling us: stop eating so the retreat is not slowed.
        if (holdingUse && inControl.filter(process -> process == this).isEmpty()) release();
    }
    @Override public void onPlayerDeath() { release(); mode = IDLE; }
    @Override public boolean isTemporary() { return true; }
    @Override public double priority() { return 90; }
    @Override public void onLostControl() { release(); mode = IDLE; }
    @Override public String displayName0() { return "Food: " + state; }

    public static void savePreference(IPlayerContext context) {
        try {
            var file = context.minecraft().gameDirectory.toPath().resolve("baritone/auto-eat.properties");
            java.nio.file.Files.createDirectories(file.getParent());
            java.nio.file.Files.writeString(file, "enabled=" + enabled + "\n");
        } catch (java.io.IOException error) { Helper.HELPER.logDirect("Could not save auto eat: " + error.getMessage()); }
    }
    public static void register(IBaritone baritone) {
        if (!preferenceLoaded) {
            preferenceLoaded = true;
            var file = baritone.getPlayerContext().minecraft().gameDirectory.toPath().resolve("baritone/auto-eat.properties");
            try {
                if (java.nio.file.Files.isRegularFile(file)) enabled = java.nio.file.Files.readString(file).contains("enabled=true");
            } catch (java.io.IOException error) { Helper.HELPER.logDirect("Could not read auto eat preference; using default."); }
        }
        baritone.getCommandManager().getRegistry().register(new AutoFoodCommand(baritone));
    }
}
