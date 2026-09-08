package org.kyowa.familyaddons.mixin;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.kyowa.familyaddons.features.KuudraBuildOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hides Hypixel's own "PROGRESS: 45%" nametags on the Kuudra build piles while
 * the Build Overlay draws its own (bigger, bold) label, so the two don't stack.
 */
@Mixin(EntityRenderer.class)
public class BuildPileNametagMixin<T extends Entity, S extends EntityRenderState> {

    @Inject(method = "extractRenderState", at = @At("RETURN"))
    private void familyaddons$hideBuildPileNametag(T entity, S state, float tickProgress, CallbackInfo ci) {
        if (state.nameTag != null && KuudraBuildOverlay.INSTANCE.shouldHideNametag(entity)) {
            state.nameTag = null;
        }
    }
}
