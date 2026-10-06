/* SPDX-License-Identifier: LGPL-3.0-or-later */
package baritone.utils;

import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import java.util.List;
import java.util.stream.Stream;

public final class DiamondPickaxeCommand extends Command {
    private final DiamondPickaxeProcess task;
    public DiamondPickaxeCommand(IBaritone baritone) {
        super(baritone,"diamondpickaxe");
        task=new DiamondPickaxeProcess(baritone);
        baritone.getPathingControlManager().registerProcess(task);
        baritone.getGameEventHandler().registerEventListener(task);
    }
    @Override public void execute(String label, IArgConsumer args) {
        args.requireMax(1);
        String action=args.hasAny() ? args.getString().toLowerCase(java.util.Locale.ROOT) : "start";
        switch(action) {
            case "start" -> task.start();
            case "stop", "cancel" -> { task.stop("Stopped"); baritone.getPathingBehavior().cancelEverything(); }
            case "status" -> logDirect(task.displayName());
            default -> logDirect("Use diamondpickaxe [start|status|stop]");
        }
    }
    @Override public Stream<String> tabComplete(String label,IArgConsumer args) { return Stream.of("start","status","stop"); }
    @Override public String getShortDesc() { return "Gather, craft and smelt from scratch to a diamond pickaxe"; }
    @Override public List<String> getLongDesc() { return List.of("diamondpickaxe: gather logs, craft planks/sticks/table, wooden and stone picks, furnace, smelt iron, craft iron pick, mine diamonds and craft diamond pick.","Existing supplies and tools are reused. Uses ordinary survival mining, placement, furnace and inventory actions.","diamondpickaxe status/stop; stop/cancel also cancel the task. Stops on death, disconnect, full inventory or sustained failure."); }
}
