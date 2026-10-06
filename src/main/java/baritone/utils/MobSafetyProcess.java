/* SPDX-License-Identifier: LGPL-3.0-or-later */
package baritone.utils;

import baritone.api.IBaritone;
import baritone.api.event.events.TickEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Helper;
import baritone.api.utils.IPlayerContext;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Temporary top-priority process. While another Baritone process is in control, a nearby hostile mob pauses it
 * (without cancelling it) and paths away through ordinary movement; control returns once the area stays clear.
 */
public final class MobSafetyProcess implements IBaritoneProcess, AbstractGameEventListener {
    private static final int SCAN = 32, CLEAR_TICKS = 20, MAX_RETREAT = 600, COOLDOWN = 100, GOAL_CHECK = 20, HYSTERESIS = 6, MAX_FROM = 16;
    private static final int MARGIN = 6, HIDE_RADIUS = 8, HIDE_HEIGHT = 3;
    private final IBaritone baritone;
    private final IPlayerContext ctx;
    private boolean automation, retreating, assessedExit;
    private int retreatTicks, clearTicks, cooldown, failures, goalAge, retreats, assessedTick = Integer.MIN_VALUE;
    private Threat assessed;
    private Mob current;
    private int quietId = -1, quietUntil, lastLookahead, lastReplan, replanWindow;
    private boolean replanRequested, emergency;
    private int emergencySince, rtpCooldownUntil, lastHurt = -10000;
    private net.minecraft.world.phys.Vec3 emergencyFrom;
    private String replanReason;
    private final java.util.Map<Integer, Integer> replans = new java.util.HashMap<>();
    private double quietDistance;
    private Escape goal;
    private String state = "idle";

    private record Threat(Mob trigger, double distance, boolean imminent, List<Mob> nearby) {}

    /** Satisfied once every threat is at least its run distance away horizontally, or a ranged one cannot see the spot. */
    private static final class Escape implements Goal {
        private final int[] xs, zs, distanceSq;
        private final boolean[] ranged;
        private final LongOpenHashSet hidden;
        Escape(List<Mob> mobs, List<BlockPos> remembered, double extra, double scale, LongOpenHashSet hidden) {
            int n = mobs.size() + remembered.size();
            xs = new int[n]; zs = new int[n]; distanceSq = new int[n]; ranged = new boolean[n];
            for (int i = 0; i < mobs.size(); i++) {
                Mob mob = mobs.get(i);
                MobSafety.Profile profile = MobSafety.profile(mob);
                double run = (profile.run() + extra) * scale;
                xs[i] = mob.getBlockX(); zs[i] = mob.getBlockZ(); distanceSq[i] = (int) (run * run); ranged[i] = profile.ranged();
            }
            // Do not flee towards a hunter seen earlier, even if it is out of view now.
            for (int j = 0; j < remembered.size(); j++) {
                int i = mobs.size() + j; double run = (16 + extra) * scale;
                xs[i] = remembered.get(j).getX(); zs[i] = remembered.get(j).getZ(); distanceSq[i] = (int) (run * run);
            }
            this.hidden = hidden;
        }
        @Override public boolean isInGoal(int x, int y, int z) {
            for (int i = 0; i < xs.length; i++) {
                int dx = x - xs[i], dz = z - zs[i];
                if (dx * dx + dz * dz >= distanceSq[i]) continue;
                if (ranged[i] && hidden.contains(BetterBlockPos.longHash(x, y, z))) continue;
                return false;
            }
            return true;
        }
        @Override public double heuristic(int x, int y, int z) {
            double min = Double.MAX_VALUE;
            for (int i = 0; i < xs.length; i++) min = Math.min(min, GoalXZ.calculate(xs[i] - x, zs[i] - z));
            return -min;
        }
        @Override public double heuristic() { return 0; }
        @Override public String toString() { return "MobSafetyEscape{threats=" + xs.length + ", hidden=" + hidden.size() + "}"; }
    }

    public MobSafetyProcess(IBaritone baritone) { this.baritone = baritone; ctx = baritone.getPlayerContext(); }

    public String state() { return state + (retreats > 0 ? "; " + retreats + " retreat(s) this session" : ""); }
    private void setState(String value) {
        if (!state.equals(value)) { state = value; Helper.HELPER.logDirect("Mob safety: " + value); }
    }
    private void reset() { retreating = false; goal = null; cooldown = 0; assessed = null; assessedTick = Integer.MIN_VALUE; }

    /** Hostiles inside their trigger distance; on exit each distance grows by the hysteresis margin. */
    private Threat assess(boolean exiting) {
        var player = ctx.player();
        if (player == null || ctx.world() == null) return null;
        if (assessedTick == player.tickCount && assessedExit == exiting) return assessed;
        assessedTick = player.tickCount; assessedExit = exiting; assessed = null;
        if (player.isCreative() || player.isSpectator() || player.isFallFlying() || player.isPassenger()) return null;
        List<Mob> nearby = new ArrayList<>();
        Mob trigger = null;
        double triggerDistance = 0;
        boolean imminent = false;
        for (Entity entity : (Iterable<Entity>) ctx.entitiesStream()::iterator) {
            if (!MobSafety.dangerous(entity, player)) continue;
            double distance = player.distanceTo(entity);
            if (distance > SCAN) continue;
            Mob mob = (Mob) entity;
            nearby.add(mob);
            MobSafety.Profile profile = MobSafety.profile(mob);
            if (Math.abs(mob.getY() - player.getY()) > profile.reachY()) continue;
            double reach = player.hasLineOfSight(mob) ? profile.sight() : profile.blind();
            // A mob just retreated from must come clearly closer before it triggers again, or retreats flap.
            boolean quiet = !exiting && mob.getId() == quietId && player.tickCount < quietUntil && !profile.urgent() && distance > quietDistance - 1.5;
            if (!quiet && distance < reach + (exiting ? HYSTERESIS : 0)) {
                boolean urgent = profile.urgent();
                if (trigger == null || urgent && !imminent || urgent == imminent && distance < triggerDistance) { trigger = mob; triggerDistance = distance; }
                imminent |= urgent;
            }
        }
        if (trigger == null) return null;
        nearby.sort(Comparator.comparingDouble(player::distanceTo));
        if (nearby.size() > MAX_FROM) nearby = new ArrayList<>(nearby.subList(0, MAX_FROM));
        return assessed = new Threat(trigger, triggerDistance, imminent, nearby);
    }

    /** Standable spots near the player whose head no ranged threat can see; computed on the client thread. */
    private LongOpenHashSet hiddenSpots(List<Mob> mobs) {
        LongOpenHashSet hidden = new LongOpenHashSet();
        List<Vec3> eyes = new ArrayList<>();
        for (Mob mob : mobs) if (MobSafety.profile(mob).ranged()) eyes.add(mob.getEyePosition());
        if (eyes.isEmpty()) return hidden;
        var world = ctx.world();
        BlockPos feet = ctx.playerFeet();
        for (int dx = -HIDE_RADIUS; dx <= HIDE_RADIUS; dx++) for (int dz = -HIDE_RADIUS; dz <= HIDE_RADIUS; dz++) for (int dy = -HIDE_HEIGHT; dy <= HIDE_HEIGHT; dy++) {
            BlockPos pos = feet.offset(dx, dy, dz);
            if (!world.getBlockState(pos).getCollisionShape(world, pos).isEmpty() || !world.getBlockState(pos.above()).getCollisionShape(world, pos.above()).isEmpty()
                    || world.getBlockState(pos.below()).getCollisionShape(world, pos.below()).isEmpty()) continue;
            Vec3 head = new Vec3(pos.getX() + 0.5, pos.getY() + 1.5, pos.getZ() + 0.5);
            boolean covered = true;
            for (Vec3 eye : eyes) {
                if (world.clip(new ClipContext(eye, head, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty())).getType() == HitResult.Type.MISS) { covered = false; break; }
            }
            if (covered) hidden.add(BetterBlockPos.longHash(pos.getX(), pos.getY(), pos.getZ()));
        }
        return hidden;
    }

    /** Drowning, in lava, or nearly dead while under attack or burning: the reason, or null. */
    private String emergencyReason() {
        var player = ctx.player();
        if (player.isCreative() || player.isSpectator() || !player.isAlive()) return null;
        if (player.isUnderWater() && player.getAirSupply() <= player.getMaxAirSupply() * 3 / 10) return "about to drown";
        if (player.isInLava()) return "in lava";
        // Any creeper close by: teleport rather than gamble on outrunning the blast.
        for (Entity entity : (Iterable<Entity>) ctx.entitiesStream()::iterator) {
            if (!(entity instanceof Creeper creeper) || !MobSafety.dangerous(entity, player)) continue;
            boolean swelling = creeper.getSwellDir() > 0 || creeper.isIgnited() || creeper.getSwelling(1f) > 0;
            if (player.distanceTo(entity) < (swelling ? 9 : 7)) return "creeper close";
        }
        if (player.getHealth() <= 6 && MobSafety.clock() - lastHurt < 60) {
            if (player.isOnFire()) return "burning on low health";
            for (Entity entity : (Iterable<Entity>) ctx.entitiesStream()::iterator)
                if (MobSafety.dangerous(entity, player) && player.distanceTo(entity) < 10) return "low health with " + entity.getName().getString() + " close";
        }
        return null;
    }

    @Override public boolean isActive() {
        if (!MobSafety.enabled() || ctx.player() == null || ctx.world() == null) { reset(); emergency = false; return false; }
        if (emergency) return true;
        if ((automation || retreating) && MobSafety.clock() >= rtpCooldownUntil && emergencyReason() != null) return true;
        if (retreating) return true;
        if (!automation) return false; // manual play is never taken over
        if (MobSafety.enclosed(ctx)) return false; // boxed in (or a one-wide shaft): breaking out would be worse
        if (replanRequested || lookAhead()) return true;
        Threat threat = assess(false);
        return threat != null && (cooldown == 0 || threat.imminent());
    }

    /** Hunters remembered from earlier that are not among the mobs currently in view. */
    private List<BlockPos> rememberedOutside(List<Mob> nearby) {
        List<BlockPos> out = new ArrayList<>();
        for (var entry : MobSafety.hunters().entrySet()) if (nearby.stream().noneMatch(m -> m.getId() == entry.getKey())) out.add(entry.getValue().pos());
        return out;
    }

    /**
     * Check the next stretch of the current path against remembered hunters; if it would walk into one, ask for a replan
     * (which now costs that area heavily) before getting there. Bounded so an unavoidable route cannot thrash.
     */
    private boolean lookAhead() {
        int now = MobSafety.clock();
        if (now - lastLookahead < 10 || now - lastReplan < 40 || MobSafety.hunters().isEmpty()) return false;
        lastLookahead = now;
        if (now - replanWindow > 1200) { replanWindow = now; replans.clear(); }
        var current = baritone.getPathingBehavior().getCurrent();
        if (current == null) return false;
        var positions = current.getPath().positions();
        int start = current.getPosition() + 2, end = Math.min(positions.size(), start + 40);
        BlockPos feet = ctx.playerFeet();
        for (var entry : MobSafety.hunters().entrySet()) {
            var hunter = entry.getValue();
            int reach = Math.max(4, hunter.radius() - 2);
            if (feet.distSqr(hunter.pos()) <= reach * reach || replans.getOrDefault(entry.getKey(), 0) >= 3) continue;
            for (int i = start; i < end; i++) {
                if (positions.get(i).distSqr(hunter.pos()) >= reach * reach) continue;
                replans.merge(entry.getKey(), 1, Integer::sum);
                replanRequested = true;
                Entity mob = ctx.world().getEntity(entry.getKey());
                replanReason = mob == null ? "a hostile seen earlier" : mob.getName().getString();
                return true;
            }
        }
        return false;
    }

    @Override public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (!emergency && MobSafety.clock() >= rtpCooldownUntil) {
            String reason = emergencyReason();
            if (reason != null) {
                // Last resort: ask the server for a random teleport and keep still, as teleport warm-ups require.
                emergency = true; emergencySince = MobSafety.clock(); emergencyFrom = ctx.player().position(); retreating = false; goal = null;
                rtpCooldownUntil = MobSafety.clock() + 2400;
                baritone.getInputOverrideHandler().clearAllKeys();
                ctx.player().connection.sendCommand("rtp");
                setState("emergency (" + reason + "): sent /rtp, standing still");
            }
        }
        if (emergency) {
            boolean moved = ctx.player().position().distanceTo(emergencyFrom) > 32;
            if (moved || MobSafety.clock() - emergencySince > 300) {
                emergency = false;
                setState(moved ? "teleported away; resuming" : "no teleport after 15 seconds; resuming");
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            if (ctx.minecraft().gui.screen() != null && MobSafety.clock() - emergencySince == 40) setState("the server opened a menu for /rtp; choose a destination");
            baritone.getInputOverrideHandler().clearAllKeys();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (replanRequested && !retreating) {
            replanRequested = false; lastReplan = MobSafety.clock();
            setState("path ahead passes " + replanReason + "; replanning around it");
            // Drop the segment; the paused task replans with the hunter's area costed heavily.
            return new PathingCommand(baritone.getPathingBehavior().getGoal(), PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        replanRequested = false;
        Threat threat = assess(retreating);
        if (!retreating) {
            if (threat == null) return new PathingCommand(null, PathingCommandType.DEFER);
            retreating = true; retreatTicks = clearTicks = failures = 0; goal = null; retreats++; current = threat.trigger();
            MobSafety.rememberHunter(threat.trigger());
            // Leave stations normally so cursor and grid items return to the inventory.
            if (ctx.player().containerMenu != ctx.player().inventoryMenu) ctx.player().closeContainer();
            setState(String.format(Locale.ROOT, "retreating from %s %.1f blocks away", threat.trigger().getName().getString(), threat.distance()));
        }
        retreatTicks++;
        if (threat == null) {
            if (++clearTicks >= CLEAR_TICKS) return finish("area clear; resuming", 0);
        } else clearTicks = 0;
        if (retreatTicks > MAX_RETREAT) return finish("still threatened after 30 seconds; resuming for now", COOLDOWN);
        if (calcFailed) {
            goal = null;
            if (++failures >= 3) return finish("no escape route found; resuming for now", COOLDOWN);
        }
        if (threat != null && (goal == null || --goalAge <= 0)) {
            goalAge = GOAL_CHECK;
            double scale = failures > 0 ? 0.5 : 1;
            LongOpenHashSet hidden = hiddenSpots(threat.nearby());
            // Keep the current route while its end is still safe from where the mobs are now; replanning stalls the escape.
            List<BlockPos> remembered = rememberedOutside(threat.nearby());
            Escape safe = new Escape(threat.nearby(), remembered, 0, scale, hidden);
            var current = baritone.getPathingBehavior().getCurrent();
            BlockPos end = current == null ? ctx.playerFeet() : current.getPath().getDest();
            if (goal == null || !safe.isInGoal(end)) goal = new Escape(threat.nearby(), remembered, MARGIN, scale, hidden);
        }
        return goal == null ? new PathingCommand(null, PathingCommandType.REQUEST_PAUSE) : new PathingCommand(goal, PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH);
    }

    private PathingCommand finish(String message, int pause) {
        retreating = false; goal = null; cooldown = pause;
        if (current != null && current.isAlive() && ctx.player() != null) {
            quietId = current.getId(); quietDistance = ctx.player().distanceTo(current); quietUntil = ctx.player().tickCount + 300;
        }
        current = null;
        setState(message);
        // Drop the retreat segment so the paused task plans afresh from here.
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    @Override public void onTick(TickEvent event) {
        if (event.getType() == TickEvent.Type.OUT) { reset(); automation = false; return; }
        MobSafety.tick();
        if (ctx.player() != null && ctx.player().hurtTime > 0) lastHurt = MobSafety.clock();
        if (MobSafety.enabled() && ctx.player() != null && MobSafety.clock() % 5 == 0) {
            var player = ctx.player();
            for (Entity entity : (Iterable<Entity>) ctx.entitiesStream()::iterator) {
                if (!(entity instanceof Mob mob)) continue;
                if (MobSafety.dangerous(entity, player)) {
                    // Aggressive and close, or already remembered: keep its position fresh.
                    if (player.distanceTo(entity) < 12 || MobSafety.hunters().containsKey(mob.getId())) MobSafety.rememberHunter(mob);
                } else if (MobSafety.hunters().containsKey(mob.getId()) && (!mob.isAlive() || player.distanceTo(mob) > 24)) MobSafety.forget(mob.getId());
            }
        }
        // Runs after PathingBehavior, so this is the process that controlled this tick.
        automation = baritone.getPathingControlManager().mostRecentInControl().filter(process -> process != this).isPresent();
        if (cooldown > 0) cooldown--;
    }
    @Override public void onPlayerDeath() { reset(); emergency = false; }
    @Override public boolean isTemporary() { return true; }
    @Override public double priority() { return 100; }
    @Override public void onLostControl() { reset(); automation = false; }
    @Override public String displayName0() { return "Mob safety: " + state; }
}
