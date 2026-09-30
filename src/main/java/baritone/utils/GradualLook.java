/* SPDX-License-Identifier: LGPL-3.0-or-later */
package baritone.utils;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.behavior.look.IAimProcessor;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.RayTraceUtils;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Bounded ground aiming shared by the live and forked aim processors. */
public final class GradualLook {
    public static final float MAX_YAW = 12f, MAX_PITCH = 8f;
    private static volatile boolean enabled = true;
    private static final Map<IPlayerContext, Rotation> interactions = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<IPlayerContext, Rotation> tickRotations = Collections.synchronizedMap(new WeakHashMap<>());
    private GradualLook() {}
    public static boolean enabled() { return enabled; }
    public static void enable(boolean value) {
        enabled = value;
        interactions.clear();
        tickRotations.clear();
        if (value) {
            var settings = BaritoneAPI.getSettings();
            settings.freeLook.value = false;
            settings.blockFreeLook.value = false;
            settings.smoothLook.value = false;
            settings.walkWhileBreaking.value = false;
            settings.allowSprint.value = false;
            settings.allowParkour.value = false;
        }
    }
    public static void register(IBaritone baritone) {
        baritone.getCommandManager().getRegistry().register(new GradualLookCommand(baritone));
        if (enabled) enable(true);
    }
    public static void beginTick(IPlayerContext context) {
        if (enabled && !context.player().isFallFlying()) tickRotations.put(context,context.playerRotations());
        else tickRotations.remove(context);
    }
    public static Rotation previous(Rotation current, IPlayerContext context) {
        if (!enabled || context.player().isFallFlying()) return current;
        Rotation recorded=tickRotations.get(context);return recorded==null ? current : recorded;
    }
    public static void clear(IPlayerContext context) { interactions.remove(context);tickRotations.remove(context); }
    public static void request(IPlayerContext context, Rotation desired, boolean interact) {
        if (interact) interactions.put(context, desired); else interactions.remove(context);
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
