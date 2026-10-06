/* SPDX-License-Identifier: LGPL-3.0-or-later */
package baritone.utils;

import baritone.api.BaritoneAPI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;


/** Fixed emergency stop, available even while the task owns a crafting screen. */
public final class StopHotkey {
    // GLFW key/action constants; no additional runtime dependency.
    private static final int KEYPAD_9=329, PRESS=1;
    private StopHotkey() {}
    public static boolean handle(long window, int action, KeyEvent event) {
        Minecraft mc=Minecraft.getInstance();
        if(mc.player==null || mc.level==null || mc.getWindow().handle()!=window || event.key()!=KEYPAD_9)return false;
        if(action==PRESS)BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("stop");
        return true;
    }
}
