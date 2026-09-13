package org.kyowa.familyaddons.mixin;

import net.minecraft.network.chat.Component;
import org.kyowa.familyaddons.features.NameStyle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;

/**
 * SkyHanni compat: its Compact Tab List reader serialises every tab line with
 * formattedTextCompat (hex colours become literal "<#rrggbb>" tags) and wraps
 * the string back in Component.literal, so custom-name letters showed up as
 * "<#FA0000>K<#FA0100>y..." in the tab. Every line ends up in a TabLine, so
 * rebuild the styled component in its constructor: that fixes both the drawn
 * text and TabLine.getWidth, which sizes the columns from the same field.
 *
 * Fields are set reflectively rather than via @Shadow so a SkyHanni rename
 * can only make this a no-op, never a mixin apply error. @Pseudo + require 0:
 * skipped when SkyHanni is absent or the constructor changes shape.
 */
@Pseudo
@Mixin(targets = "at.hannibal2.skyhanni.features.misc.compacttablist.TabLine")
public class SkyHanniTabLineCompatMixin {

    private static Field familyaddons$component;
    private static Field familyaddons$customName;
    private static boolean familyaddons$lookedUp;

    @Inject(
        method = "<init>(Lnet/minecraft/network/chat/Component;Lat/hannibal2/skyhanni/features/misc/compacttablist/TabStringType;Lnet/minecraft/network/chat/Component;)V",
        at = @At("RETURN"),
        require = 0
    )
    private void familyaddons$restoreHexColours(CallbackInfo ci) {
        if (!familyaddons$lookedUp) {
            familyaddons$lookedUp = true;
            familyaddons$component = familyaddons$field("component");
            familyaddons$customName = familyaddons$field("customName");
        }
        familyaddons$fix(familyaddons$component);
        familyaddons$fix(familyaddons$customName);
    }

    /** The field on the real TabLine class (this mixin is merged into it), or null if SkyHanni renamed it. */
    private Field familyaddons$field(String name) {
        try {
            Field f = this.getClass().getDeclaredField(name);
            if (f.getType() != Component.class) return null;
            f.setAccessible(true);
            return f;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void familyaddons$fix(Field f) {
        if (f == null) return;
        try {
            Object v = f.get(this);
            if (!(v instanceof Component c)) return;
            Component fixed = NameStyle.INSTANCE.fromHexTagged(c.getString());
            if (fixed != null) f.set(this, fixed);
        } catch (Throwable ignored) {
        }
    }
}
