package org.kyowa.familyaddons.mixin;

import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.kyowa.familyaddons.features.NameStyle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tab list in lobbies: Hypixel sends no tab display name there, so vanilla
 * builds "[MVP+] Name [GUILD]" from the scoreboard team inside
 * getNameForDisplay. That path never touches PlayerInfo.getTabListDisplayName
 * (which NameStyleTabMixin hooks for SkyBlock), so restyle the result here.
 */
@Mixin(PlayerTabOverlay.class)
public class NameStyleTabOverlayMixin {

    @Inject(method = "getNameForDisplay", at = @At("RETURN"), cancellable = true)
    private void familyaddons$styleTeamName(PlayerInfo info, CallbackInfoReturnable<Component> cir) {
        if (info.getTabListDisplayName() != null) return; // SkyBlock: already handled by NameStyleTabMixin
        Component name = cir.getReturnValue();
        if (name == null) return;
        Component styled = NameStyle.INSTANCE.restyle(name);
        if (styled != name) cir.setReturnValue(styled);
    }
}
