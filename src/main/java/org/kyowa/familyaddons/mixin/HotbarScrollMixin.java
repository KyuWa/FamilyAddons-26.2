package org.kyowa.familyaddons.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.kyowa.familyaddons.config.FamilyConfigManager;

@Mixin(MouseHandler.class)
public class HotbarScrollMixin {

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void onScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        if (!FamilyConfigManager.INSTANCE.getConfig().utilities.lockHotbarScroll) return;

        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return;
        if (client.gui.screen() != null) return;

        int slot = client.player.getInventory().getSelectedSlot();

        if (vertical < 0 && slot == 8) {
            ci.cancel();
            return;
        }

        if (vertical > 0 && slot == 0) {
            ci.cancel();
        }
    }
}
