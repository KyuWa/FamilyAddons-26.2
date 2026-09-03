package org.kyowa.familyaddons.mixin;

import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.kyowa.familyaddons.EntityRefAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(LivingEntityRenderState.class)
public class EntityRefMixin implements EntityRefAccessor {

    @Unique
    private LivingEntity familyaddons$entityRef;

    @Override
    public LivingEntity familyaddons$getEntity() { return familyaddons$entityRef; }

    @Override
    public void familyaddons$setEntity(LivingEntity e) { familyaddons$entityRef = e; }
}