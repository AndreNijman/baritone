/* SPDX-License-Identifier: LGPL-3.0-or-later */
package baritone.utils;

import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import java.util.List;
import java.util.stream.Stream;

/** Standalone food run: hunt nearby farm animals for meat, eat if hungry, then stop. */
public final class GetFoodCommand extends Command {
    private final AutoFood process;
    public GetFoodCommand(IBaritone baritone, AutoFood process) {
        super(baritone, "getfood");
        this.process = process;
    }
    @Override public void execute(String label, IArgConsumer args) {
        try {
            args.requireMax(1);
            String action = args.hasAny() ? args.getString().toLowerCase(java.util.Locale.ROOT) : "8";
            switch (action) {
                case "stop", "cancel" -> { process.cancel(); baritone.getPathingBehavior().cancelEverything(); }
                case "status" -> logDirect("Food: " + process.state());
                default -> {
                    int count = Integer.parseInt(action);
                    if (count < 1 || count > 64) { logDirect("Use getfood [1-64|stop|status]"); return; }
                    baritone.getPathingBehavior().cancelEverything();
                    process.request(count);
                }
            }
        } catch (NumberFormatException error) { logDirect("Use getfood [count|stop|status]"); }
    }
    @Override public Stream<String> tabComplete(String label, IArgConsumer args) { return Stream.of("8", "16", "stop", "status"); }
    @Override public String getShortDesc() { return "Hunt nearby farm animals for food"; }
    @Override public List<String> getLongDesc() {
        return List.of("getfood [count]: hunt the nearest cows, pigs, sheep, chickens and rabbits (within 48 blocks) until holding count food items (default 8), collect the meat, eat if hungry, then stop.",
                "getfood stop (or stop / Numpad 9) cancels; getfood status reports progress. Attacks only with the crosshair on the animal and a full charge; mob safety still applies.");
    }
}
