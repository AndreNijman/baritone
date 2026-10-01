/* SPDX-License-Identifier: LGPL-3.0-or-later */
package baritone.utils;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.behavior.look.IAimProcessor;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.input.Input;
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
    private static final Map<IPlayerContext, Rotation> travelTargets = Collections.synchronizedMap(new WeakHashMap<>());
    private GradualLook() {}
    public static boolean enabled() { return enabled; }
    public static void enable(boolean value) {
        enabled = value;
        interactions.clear();
        tickRotations.clear();
        travelTargets.clear();
        if (value) {
            var settings = BaritoneAPI.getSettings();
            settings.freeLook.value = false;
            settings.blockFreeLook.value = false;
            settings.smoothLook.value = false;
            settings.walkWhileBreaking.value = false;
        }
    }
    public static void register(IBaritone baritone) {
        baritone.getCommandManager().getRegistry().register(new GradualLookCommand(baritone));
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
        if (desired == null || !context.player().onGround()) return;
        IBaritone baritone=BaritoneAPI.getProvider().getBaritoneForPlayer(context.player());
        if (baritone == null) return;
        var input=baritone.getInputOverrideHandler();
        // Jump timing and sprint-jump impulse remain the path executor's responsibility.
        if (input.isInputForcedDown(Input.JUMP)) return;
        int mask=(input.isInputForcedDown(Input.MOVE_FORWARD)?1:0) | (input.isInputForcedDown(Input.MOVE_BACK)?2:0)
                | (input.isInputForcedDown(Input.MOVE_LEFT)?4:0) | (input.isInputForcedDown(Input.MOVE_RIGHT)?8:0);
        Rotation actual=baritone.getLookBehavior().getAimProcessor().peekRotation(desired);
        int selected=steeringKeys(mask, desired.getYaw(), actual.getYaw());
        input.setInputForceState(Input.MOVE_FORWARD,(selected&1)!=0);
        input.setInputForceState(Input.MOVE_BACK,(selected&2)!=0);
        input.setInputForceState(Input.MOVE_LEFT,(selected&4)!=0);
        input.setInputForceState(Input.MOVE_RIGHT,(selected&8)!=0);
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
