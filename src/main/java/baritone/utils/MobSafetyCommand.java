/* SPDX-License-Identifier: LGPL-3.0-or-later */
package baritone.utils;

import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import java.util.List;
import java.util.stream.Stream;

/** Persistent hostile-mob avoidance toggle. */
public final class MobSafetyCommand extends Command {
    private final MobSafetyProcess process;
    public MobSafetyCommand(IBaritone baritone) {
        super(baritone, "avoidmobs", "mobsafety");
        process = new MobSafetyProcess(baritone);
        baritone.getPathingControlManager().registerProcess(process);
        baritone.getGameEventHandler().registerEventListener(process);
    }
    @Override public void execute(String label, IArgConsumer args) {
        try {
            args.requireMax(1);
            String action = args.hasAny() ? args.getString().toLowerCase(java.util.Locale.ROOT) : "toggle";
            switch (action) {
                case "on" -> MobSafety.enable(true);
                case "off" -> MobSafety.enable(false);
                case "toggle" -> MobSafety.enable(!MobSafety.enabled());
                case "status" -> {}
                default -> { logDirect("Use avoidmobs on, off, toggle, or status"); return; }
            }
            if (!action.equals("status")) MobSafety.savePreference(ctx);
            logDirect("Hostile mob avoidance: " + (MobSafety.enabled() ? "on" : "off") + "; " + process.state());
        } catch (Exception error) { logDirect("Use avoidmobs on, off, toggle, or status"); }
    }
    @Override public Stream<String> tabComplete(String label, IArgConsumer args) { return Stream.of("on", "off", "toggle", "status"); }
    @Override public String getShortDesc() { return "Toggle hostile mob avoidance during Baritone tasks"; }
    @Override public List<String> getLongDesc() {
        return List.of("avoidmobs: toggle hostile mob avoidance; on/off/toggle/status also supported. Defaults on and survives restart.",
                "While any Baritone task runs, paths cost more near hostile mobs and spawners, and a nearby hostile with line of sight, a swelling creeper or a warden pauses the task to retreat.",
                "The task resumes once the area stays clear. Passive animals, villagers and calm neutral mobs are ignored. It never attacks; manual play is untouched.");
    }
}
