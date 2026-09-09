package org.kyowa.familyaddons.mixin;

import net.minecraft.network.chat.Style;
import org.kyowa.familyaddons.features.NameStyle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Per-glyph hook: the owner-name gradient letters carry a marker colour (see
 * NameStyle.gradient); here, every frame, that marker is swapped for the live
 * sweeping colour. Chat lines are stored once, so this is what lets them move.
 */
@Mixin(targets = "net.minecraft.client.gui.Font$PreparedTextBuilder")
public class NameStyleFontMixin {

    @ModifyVariable(method = "accept(ILnet/minecraft/network/chat/Style;I)Z", at = @At("HEAD"), argsOnly = true)
    private Style familyaddons$animateGradient(Style style) {
        return NameStyle.INSTANCE.animate(style);
    }
}
