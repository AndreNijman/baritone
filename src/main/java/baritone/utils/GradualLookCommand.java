/* SPDX-License-Identifier: LGPL-3.0-or-later */
package baritone.utils;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import java.util.List;
import java.util.stream.Stream;
/** Session toggle; this is an aiming controller, not a detection guarantee. */
public final class GradualLookCommand extends Command {
    public GradualLookCommand(IBaritone baritone) { super(baritone, "gradual"); }
    @Override public void execute(String label, IArgConsumer args) {
        try {
        args.requireMax(1);
        String action = args.hasAny() ? args.getString().toLowerCase(java.util.Locale.ROOT) : "status";
        switch (action) {
            case "on" -> GradualLook.enable(true);
            case "off" -> GradualLook.enable(false);
            case "status" -> {}
            default -> { logDirect("Use gradual on, off, or status"); return; }
        }
        logDirect("Gradual ground aiming: " + (GradualLook.enabled() ? "on" : "off") + "; caps " + GradualLook.MAX_YAW + " yaw / " + GradualLook.MAX_PITCH + " pitch degrees per tick.");
        } catch (Exception error) { logDirect("Use gradual on, off, or status"); }
    }
    @Override public Stream<String> tabComplete(String label, IArgConsumer args) { return Stream.of("on", "off", "status"); }
    @Override public String getShortDesc() { return "Toggle gradual ground aiming and matching-view mining"; }
    @Override public List<String> getLongDesc() { return List.of("gradual on: bounded transmitted ground rotations, matching camera, stationary breaking.", "gradual off: disable the rotation controller; current settings remain as configured.", "Session toggle; enabled by default on restart. Flight is unaffected."); }
}
