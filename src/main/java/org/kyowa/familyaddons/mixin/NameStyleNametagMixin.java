package org.kyowa.familyaddons.mixin;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.kyowa.familyaddons.features.NameStyle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Nametags and armor-stand holograms: swap known IGNs for their custom names. */
@Mixin(EntityRenderer.class)
public class NameStyleNametagMixin<T extends Entity, S extends EntityRenderState> {

    @Inject(method = "extractRenderState", at = @At("RETURN"))
    private void familyaddons$styleNametag(T entity, S state, float tickProgress, CallbackInfo ci) {
        // Players AND holograms (armor stand names: "zMusu's Honeyhive", shop / lobby text).
        if (state.nameTag == null) return;
        state.nameTag = NameStyle.INSTANCE.restyle(state.nameTag);
    }
}
