package org.kyowa.familyaddons;

import net.minecraft.world.entity.LivingEntity;

/**
 * Mixed into LivingEntityRenderState so we can retrieve the source entity during rendering.
 */
public interface EntityRefAccessor {
    LivingEntity familyaddons$getEntity();
    void familyaddons$setEntity(LivingEntity entity);
}