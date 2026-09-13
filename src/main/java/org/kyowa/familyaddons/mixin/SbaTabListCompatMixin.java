package org.kyowa.familyaddons.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import org.kyowa.familyaddons.features.NameStyle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * SkyblockAddons compat: its Compact Tab List flattens every tab entry to a
 * legacy § string (TextUtils.getFormattedText) when parsing, then lays each
 * line out with FormattedText.of(String) in TabListRenderer.render. Hex
 * colours cannot survive that round trip, so custom names came out white
 * there while chat, nametags and the vanilla / Skyblocker tab were fine.
 * Re-apply the names on the string at the moment SBA turns it back into text.
 *
 * @Pseudo + require = 0: silently skipped when SBA is not installed or its
 * renderer changes shape; never blocks the game from launching.
 */
@Pseudo
@Mixin(targets = "com.fix3dll.skyblockaddons.features.tablist.TabListRenderer")
public class SbaTabListCompatMixin {

    @Redirect(
        method = "render",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/network/chat/FormattedText;of(Ljava/lang/String;)Lnet/minecraft/network/chat/FormattedText;"),
        require = 0
    )
    private static FormattedText familyaddons$restyleTabLine(String text) {
        return NameStyle.INSTANCE.restyle(Component.literal(text));
    }
}
