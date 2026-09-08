package org.kyowa.familyaddons.mixin;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.kyowa.familyaddons.features.NameStyle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Nametag above the head: apply the owner name gradient. */
@Mixin(EntityRenderer.class)
public class NameStyleNametagMixin<T extends Entity, S extends EntityRenderState> {

    @Inject(method = "extractRenderState", at = @At("RETURN"))
    private void familyaddons$styleNametag(T entity, S state, float tickProgress, CallbackInfo ci) {
        if (!(entity instanceof Player) || state.nameTag == null) return;
        state.nameTag = NameStyle.INSTANCE.restyle(state.nameTag);
    }
}
