package org.kyowa.familyaddons.mixin;

import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.kyowa.familyaddons.features.NameStyle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Tab list: apply the owner name gradient to the display name. */
@Mixin(PlayerInfo.class)
public class NameStyleTabMixin {

    @Inject(method = "getTabListDisplayName", at = @At("RETURN"), cancellable = true)
    private void familyaddons$styleTabName(CallbackInfoReturnable<Component> cir) {
        Component name = cir.getReturnValue();
        if (name == null) return;
        Component styled = NameStyle.INSTANCE.restyle(name);
        if (styled != name) cir.setReturnValue(styled);
    }
}
