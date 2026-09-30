/* SPDX-License-Identifier: LGPL-3.0-or-later */
package baritone.launch.mixins;

import baritone.launch.privacy.SignTextPrivacy;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(AbstractSignEditScreen.class)
public abstract class MixinSignEditScreen {
    @Redirect(
            method = "<init>(Lnet/minecraft/world/level/block/entity/SignBlockEntity;Lnet/minecraft/world/level/block/entity/SignTextSlot;ZLnet/minecraft/network/chat/Component;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/network/chat/Component;getString()Ljava/lang/String;"),
            require = 1,
            allow = 1
    )
    private String baritone$privateSignText(Component component) {
        return SignTextPrivacy.getString(component);
    }
}
