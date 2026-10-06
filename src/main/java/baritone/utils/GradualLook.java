/* SPDX-License-Identifier: LGPL-3.0-or-later */
package baritone.utils;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.utils.Helper;
import baritone.api.behavior.look.IAimProcessor;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.input.Input;
import baritone.api.utils.Rotation;
import baritone.api.utils.RayTraceUtils;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.phys.HitResult;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Bounded ground aiming shared by the live and forked aim processors. */
public final class GradualLook {
    public static final float MAX_YAW = 12f, MAX_PITCH = 8f;
    private static volatile boolean enabled = true;
    private static boolean preferenceLoaded;
    private static boolean[] previousSettings;
    private static final Map<IPlayerContext, Rotation> interactions = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<IPlayerContext, Rotation> tickRotations = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<IPlayerContext, Rotation> travelTargets = Collections.synchronizedMap(new WeakHashMap<>());
    private GradualLook() {}
    public static boolean enabled() { return enabled; }
    public static void enable(boolean value) {
        var settings = value || previousSettings != null ? BaritoneAPI.getSettings() : null;
        if (value && previousSettings == null) previousSettings = new boolean[]{settings.freeLook.value, settings.blockFreeLook.value, settings.smoothLook.value, settings.walkWhileBreaking.value};
        enabled = value;
        interactions.clear();
        tickRotations.clear();
        travelTargets.clear();
        if (value) {
            settings.freeLook.value = false;
            settings.blockFreeLook.value = false;
            settings.smoothLook.value = false;
            settings.walkWhileBreaking.value = false;
        } else if (previousSettings != null) {
            if (!settings.freeLook.value) settings.freeLook.value = previousSettings[0];
            if (!settings.blockFreeLook.value) settings.blockFreeLook.value = previousSettings[1];
            if (!settings.smoothLook.value) settings.smoothLook.value = previousSettings[2];
            if (!settings.walkWhileBreaking.value) settings.walkWhileBreaking.value = previousSettings[3];
            previousSettings = null;
        }
    }
    public static void savePreference(IPlayerContext context) {
        try {
            var file = context.minecraft().gameDirectory.toPath().resolve("baritone/realistic-movement.properties");
            java.nio.file.Files.createDirectories(file.getParent());
            var temporary = file.resolveSibling("realistic-movement.properties.tmp");
            java.nio.file.Files.writeString(temporary, "enabled=" + enabled + "\n");
            java.nio.file.Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException error) { Helper.HELPER.logDirect("Could not save realistic movement: " + error.getMessage()); }
    }
    public static void register(IBaritone baritone) {
        if (!preferenceLoaded) {
            preferenceLoaded = true;
            var file = baritone.getPlayerContext().minecraft().gameDirectory.toPath().resolve("baritone/realistic-movement.properties");
            try {
                if (java.nio.file.Files.isRegularFile(file)) {
                    var properties = new java.util.Properties();
                    try (var reader = java.nio.file.Files.newBufferedReader(file)) { properties.load(reader); }
                    enabled = Boolean.parseBoolean(properties.getProperty("enabled", "true"));
                }
            } catch (java.io.IOException error) { Helper.HELPER.logDirect("Could not read realistic movement preference; using default."); }
        }
        baritone.getCommandManager().getRegistry().register(new GradualLookCommand(baritone));
        baritone.getCommandManager().getRegistry().register(new DiamondPickaxeCommand(baritone));
        MobSafety.register(baritone);
        if (enabled) enable(true);
    }
    public static void beginTick(IPlayerContext context) {
        travelTargets.remove(context);
        if (enabled && !context.player().isFallFlying()) tickRotations.put(context,context.playerRotations());
        else tickRotations.remove(context);
    }
    public static Rotation previous(Rotation current, IPlayerContext context) {
        if (!enabled || context.player().isFallFlying()) return current;
        Rotation recorded=tickRotations.get(context);return recorded==null ? current : recorded;
    }
    public static void clear(IPlayerContext context) { interactions.remove(context);tickRotations.remove(context);travelTargets.remove(context); }
    public static void request(IPlayerContext context, Rotation desired, boolean interact) {
        if (interact) { interactions.put(context, desired); travelTargets.remove(context); }
        else { interactions.remove(context); travelTargets.put(context, desired); }
    }
    /** Preserve a pure peek operation: this does not advance live or simulation state. */
    public static Rotation limit(Rotation actual, Rotation previous, IPlayerContext context) {
        if (!enabled || context.player().isFallFlying()) return actual;
        double sensitivity = context.minecraft().options.sensitivity().get();
        return step(previous, actual, sensitivity);
    }
    public static Rotation step(Rotation previous, Rotation desired, double sensitivity) {
        double f = sensitivity * (double)0.6f + (double)0.2f;
        float quantum = (float)(f*f*f*8d) * 0.15f;
        float yaw = previous.getYaw() + axis(Rotation.normalizeYaw(desired.getYaw()-previous.getYaw()), MAX_YAW, quantum);
        float pitch = previous.getPitch() + axis(desired.getPitch()-previous.getPitch(), MAX_PITCH, quantum);
        return new Rotation(yaw, pitch).clamp();
    }
    /** Geometric reachability asks whether a block can be reached after turning, not in the next tick. */
    public static Rotation reachableRotation(IAimProcessor aim, Rotation desired, IPlayerContext context) {
        if (!enabled || context.player().isFallFlying()) return aim.peekRotation(desired);
        Rotation previous=context.playerRotations();
        double f=context.minecraft().options.sensitivity().get()*(double)0.6f+(double)0.2f;
        float q=(float)(f*f*f*8d)*0.15f;
        return new Rotation(previous.getYaw()+Math.round(Rotation.normalizeYaw(desired.getYaw()-previous.getYaw())/q)*q,
                previous.getPitch()+Math.round((desired.getPitch()-previous.getPitch())/q)*q).clamp();
    }
    private static float axis(float error, float maximum, float quantum) {
        float desired = Math.abs(error) < quantum*2 ? error : error*0.35f;
        desired = Math.max(-maximum, Math.min(maximum, desired));
        int maxPixels = Math.max(1, (int)Math.floor(maximum/quantum));
        int pixels = Math.max(-maxPixels, Math.min(maxPixels, Math.round(desired/quantum)));
        return pixels * quantum;
    }
    /** Use ordinary keyboard directions relative to this tick's visible heading. */
    public static void steerInput(IPlayerContext context) {
        if (context == null || !enabled || context.player().isFallFlying() || context.player().isPassenger()) return;
        Rotation desired=travelTargets.get(context);
        if (desired == null) return;
        IBaritone baritone=BaritoneAPI.getProvider().getBaritoneForPlayer(context.player());
        if (baritone == null) return;
        var input=baritone.getInputOverrideHandler();
        // In water, JUMP is buoyancy input, not a land sprint-jump impulse.
        if (!canSteer(context.player().onGround(), context.player().isInWater() || waterMovement(baritone, context), input.isInputForcedDown(Input.JUMP))) return;
        int mask=(input.isInputForcedDown(Input.MOVE_FORWARD)?1:0) | (input.isInputForcedDown(Input.MOVE_BACK)?2:0)
                | (input.isInputForcedDown(Input.MOVE_LEFT)?4:0) | (input.isInputForcedDown(Input.MOVE_RIGHT)?8:0);
        Rotation actual=baritone.getLookBehavior().getAimProcessor().peekRotation(desired);
        int selected=steeringKeys(mask, desired.getYaw(), actual.getYaw());
        input.setInputForceState(Input.MOVE_FORWARD,(selected&1)!=0);
        input.setInputForceState(Input.MOVE_BACK,(selected&2)!=0);
        input.setInputForceState(Input.MOVE_LEFT,(selected&4)!=0);
        input.setInputForceState(Input.MOVE_RIGHT,(selected&8)!=0);
    }
    /** Keep water steering during surface bobbing and the current bank-exit movement. */
    private static boolean waterMovement(IBaritone baritone, IPlayerContext context) {
        var executor=baritone.getPathingBehavior().getCurrent();
        if (executor == null) return false;
        var movements=executor.getPath().movements();
        int index=executor.getPosition();
        if (index<0 || index>=movements.size()) return false;
        var movement=movements.get(index);
        return context.world().getFluidState(movement.getSrc()).is(FluidTags.WATER)
                || context.world().getFluidState(movement.getDest()).is(FluidTags.WATER);
    }
    public static boolean canSteer(boolean grounded, boolean inWater, boolean jumping) {
        return inWater || grounded && !jumping;
    }
    /** Eight digital keyboard headings; preserves the requested world direction within 22.5 degrees. */
    public static int steeringKeys(int mask, float desiredYaw, float actualYaw) {
        int forward=((mask&1)!=0?1:0)-((mask&2)!=0?1:0);
        int left=((mask&4)!=0?1:0)-((mask&8)!=0?1:0);
        if (forward==0 && left==0) return mask;
        float error=Rotation.normalizeYaw(desiredYaw-actualYaw);
        if (Math.abs(error)<22.5f) return mask;
        double relative=error-Math.toDegrees(Math.atan2(left,forward));
        double angle=Math.toRadians(Math.round(relative/45d)*45d);
        int f=(int)Math.round(Math.cos(angle)), l=(int)Math.round(-Math.sin(angle));
        return (mask&~15) | (f>0?1:f<0?2:0) | (l>0?4:l<0?8:0);
    }
    /** Wait for the actual view ray to meet the requested block; abort interrupted digging normally. */
    public static boolean allowMining(boolean requested, IPlayerContext context, boolean wasHitting) {
        if (!requested || !enabled || context.player().isFallFlying()) return requested;
        Rotation desired = interactions.get(context);
        if (desired == null) return requested;
        var current = context.objectMouseOver();
        var wanted = RayTraceUtils.rayTraceTowards(context.player(), desired, context.playerController().getBlockReachDistance());
        if (current instanceof BlockHitResult a && wanted instanceof BlockHitResult b && a.getType()==HitResult.Type.BLOCK && b.getType()==HitResult.Type.BLOCK && a.getBlockPos().equals(b.getBlockPos())) return true;
        if (wasHitting) {
            context.playerController().setHittingBlock(true);
            context.playerController().resetBlockRemoving();
            context.playerController().setHittingBlock(false);
        }
        return false;
    }
}
