package org.kyowa.familyaddons.mixin;

import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.MouseButtonEvent;
import org.kyowa.familyaddons.features.safari.HideyhoQuickAccept;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hideyho quick accept: while chat is open and the one-shot is armed, the first
 * left click anywhere fires [Sure]'s click event instead of whatever was under
 * the cursor. See HideyhoQuickAccept.
 */
@Mixin(ChatScreen.class)
public class ChatScreenClickMixin {

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void familyaddons$hideyhoQuickAccept(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        if (event.button() != 0) return;
        if (HideyhoQuickAccept.INSTANCE.onChatClick()) cir.setReturnValue(true);
    }
}
