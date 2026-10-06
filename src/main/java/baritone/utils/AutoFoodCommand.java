/* SPDX-License-Identifier: LGPL-3.0-or-later */
package baritone.utils;

import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import java.util.List;
import java.util.stream.Stream;

/** Persistent toggle for eating (and, during diamondpickaxe, hunting) while Baritone tasks run. */
public final class AutoFoodCommand extends Command {
    private final AutoFood process;
    public AutoFoodCommand(IBaritone baritone) {
        super(baritone, "autoeat", "autofood");
        process = new AutoFood(baritone);
        baritone.getPathingControlManager().registerProcess(process);
        baritone.getGameEventHandler().registerEventListener(process);
    }
    @Override public void execute(String label, IArgConsumer args) {
        try {
            args.requireMax(1);
            String action = args.hasAny() ? args.getString().toLowerCase(java.util.Locale.ROOT) : "toggle";
            switch (action) {
                case "on" -> AutoFood.enable(true);
                case "off" -> AutoFood.enable(false);
                case "toggle" -> AutoFood.enable(!AutoFood.enabled());
                case "status" -> {}
                default -> { logDirect("Use autoeat on, off, toggle, or status"); return; }
            }
            if (!action.equals("status")) AutoFood.savePreference(ctx);
            logDirect("Auto eat: " + (AutoFood.enabled() ? "on" : "off") + "; " + process.state());
        } catch (Exception error) { logDirect("Use autoeat on, off, toggle, or status"); }
    }
    @Override public Stream<String> tabComplete(String label, IArgConsumer args) { return Stream.of("on", "off", "toggle", "status"); }
    @Override public String getShortDesc() { return "Toggle eating and food gathering during Baritone tasks"; }
    @Override public List<String> getLongDesc() {
        return List.of("autoeat: toggle eating while Baritone tasks run; on/off/toggle/status also supported. Defaults on and survives restart.",
                "Eats the best safe food when hunger drops to 14 (or when hurt), until hunger reaches 18. Never eats spider eyes, pufferfish, poisonous potatoes, chorus fruit or golden apples; rotten flesh and raw chicken only when starving.",
                "During diamondpickaxe, with no food it hunts nearby cows, pigs, sheep, chickens and rabbits and collects the meat.");
    }
}
