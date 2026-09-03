package org.kyowa.familyaddons.mixin;

import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.kyowa.familyaddons.EntityRefAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntityRenderer.class)
public class EntityCaptureMixin<T extends LivingEntity,
        S extends LivingEntityRenderState,
        M extends Model<?>> {

    @Inject(
            method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
            at = @At("TAIL")
    )
    private void captureEntity(T entity, S state, float partialTick, CallbackInfo ci) {
        ((EntityRefAccessor) state).familyaddons$setEntity(entity);
    }
}