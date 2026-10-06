/* SPDX-License-Identifier: LGPL-3.0-or-later */
package baritone.utils;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import java.util.List;
import java.util.stream.Stream;
/** Persistent movement toggle. */
public final class GradualLookCommand extends Command {
    public GradualLookCommand(IBaritone baritone) { super(baritone, "gradual", "realistic"); }
    @Override public void execute(String label, IArgConsumer args) {
        try {
        args.requireMax(1);
        String action = args.hasAny() ? args.getString().toLowerCase(java.util.Locale.ROOT) : (label.equalsIgnoreCase("realistic") ? "toggle" : "status");
        switch (action) {
            case "on" -> GradualLook.enable(true);
            case "off" -> GradualLook.enable(false);
            case "toggle" -> GradualLook.enable(!GradualLook.enabled());
            case "status" -> {}
            default -> { logDirect("Use realistic on, off, toggle, or status"); return; }
        }
        if (!action.equals("status")) GradualLook.savePreference(ctx);
        logDirect("Gradual ground aiming: " + (GradualLook.enabled() ? "on" : "off") + "; caps " + GradualLook.MAX_YAW + " yaw / " + GradualLook.MAX_PITCH + " pitch degrees per tick.");
        } catch (Exception error) { logDirect("Use realistic on, off, toggle, or status"); }
    }
    @Override public Stream<String> tabComplete(String label, IArgConsumer args) { return Stream.of("on", "off", "toggle", "status"); }
    @Override public String getShortDesc() { return "Toggle gradual ground aiming and matching-view mining"; }
    @Override public List<String> getLongDesc() { return List.of("realistic: toggle movement realism; on/off/toggle/status also supported.", "Off disables gradual turns, travel correction and the matching-view mining gate and restores the prior movement preset.", "The toggle survives restart. Sprint and parkour remain unchanged. gradual is a compatible alias."); }
}
