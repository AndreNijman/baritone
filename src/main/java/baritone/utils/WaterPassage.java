/* SPDX-License-Identifier: LGPL-3.0-or-later */
package baritone.utils;

import baritone.api.BaritoneAPI;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.input.Input;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.WaterFluid;

/** Passability of shallow flowing water, e.g. a lake spilling one block deep into a two-high tunnel. */
public final class WaterPassage {
    private WaterPassage() {}

    /**
     * Upstream {@code canWalkThroughPosition} rejects any water that is, or touches, a flowing block. Keep that for lava,
     * falling water, submerged water and water over a drop; allow a horizontally flowing water layer with open space above
     * and a floor or more water below, which the player can wade or swim through.
     */
    /**
     * Upstream {@code canWalkThroughBlockState} answers NO for every non-source fluid state before positions are looked at.
     * Report horizontally flowing water as full so the position-dependent check below decides.
     */
    public static int stateAmount(int amount, BlockState state) {
        FluidState fluid = state.getFluidState();
        if (amount == 8 || !(fluid.getType() instanceof WaterFluid)) return amount;
        return fluid.hasProperty(FlowingFluid.FALLING) && fluid.getValue(FlowingFluid.FALLING) ? amount : 8;
    }

    public static boolean blocks(boolean flowing, BlockState state, BlockState up, BlockState below) {
        if (!flowing) return false;
        FluidState fluid = state.getFluidState();
        if (!(fluid.getType() instanceof WaterFluid)) return true;
        if (fluid.hasProperty(FlowingFluid.FALLING) && fluid.getValue(FlowingFluid.FALLING)) return true;
        if (!up.getFluidState().isEmpty()) return true;
        return !(below.isSolid() || below.getFluidState().getType() instanceof WaterFluid);
    }

    /**
     * Movement.update holds jump while the player is below {@code dest.y + 0.6} in liquid. Entering a wet two-high space,
     * that lifts the head into the ceiling and the climb-out push lands the player on the wall above the opening.
     * There, only float up to the floor so the head clears.
     */
    public static double afloat(IPlayerContext ctx, BetterBlockPos dest) {
        var world = ctx.world();
        if (world == null || world.getFluidState(dest).isEmpty() || !world.getFluidState(dest.above()).isEmpty()) return 0.6;
        BlockPos ceiling = dest.above(2);
        return world.getBlockState(ceiling).getCollisionShape(world, ceiling).isEmpty() ? 0.6 : 0.05;
    }

    /**
     * Touching the wall around such an opening while in water triggers Minecraft's climb-out push, which lifts the player
     * onto the wall instead of in, and a current flowing into the opening keeps pressing it there. Until the feet are
     * between the floor and the height at which the head clears the ceiling, back away from the opening instead of pushing
     * into it; below that window the float jump still raises the player.
     */
    public static void settle(IPlayerContext ctx, BetterBlockPos dest) {
        var player = ctx.player();
        if (player == null || !player.isInWater() || ctx.playerFeet().equals(dest) || afloat(ctx, dest) == 0.6) return;
        double y = player.getY();
        if (y >= dest.y - 0.02 && y <= dest.y + 0.15) return;
        BlockPos feet = ctx.playerFeet();
        int dx = dest.x - feet.getX(), dz = dest.z - feet.getZ();
        if (Math.abs(dx) + Math.abs(dz) != 1) return;
        // Gap between the player's box and the opening's plane; stay in the source block while sinking or rising.
        double gap = dx > 0 ? dest.x - (player.getX() + 0.3) : dx < 0 ? player.getX() - 0.3 - (dest.x + 1)
                : dz > 0 ? dest.z - (player.getZ() + 0.3) : player.getZ() - 0.3 - (dest.z + 1);
        var input = BaritoneAPI.getProvider().getBaritoneForPlayer(player).getInputOverrideHandler();
        for (Input key : new Input[]{Input.MOVE_FORWARD, Input.MOVE_LEFT, Input.MOVE_RIGHT, Input.SPRINT}) input.setInputForceState(key, false);
        input.setInputForceState(Input.MOVE_BACK, gap < 0.12);
        if (y > dest.y) input.setInputForceState(Input.JUMP, false);
    }
}
