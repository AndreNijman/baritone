/* SPDX-License-Identifier: LGPL-3.0-or-later */
package baritone.launch.mixins;

import baritone.utils.StopHotkey;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public abstract class MixinKeyboardHandler {
    @Inject(method="keyPress(JILnet/minecraft/client/input/KeyEvent;)V",at=@At("HEAD"),cancellable=true,require=1)
    private void baritone$stopHotkey(long window,int action,KeyEvent event,CallbackInfo ci) {
        if(StopHotkey.handle(window,action,event))ci.cancel();
    }
}
