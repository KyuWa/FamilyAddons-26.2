package org.kyowa.familyaddons.mixin;

import net.minecraft.world.entity.Entity;
import org.kyowa.familyaddons.features.DungeonHighlight;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The glow outline only exists on entities Minecraft renders, and it stops
 * rendering a mob past its size-based distance (scaled by the Entity Distance
 * option). For mobs DungeonHighlight has picked, answer "render it" at any
 * distance so the outline reaches as far as the mob is loaded.
 */
@Mixin(Entity.class)
public class DungeonOutlineRangeMixin {

    @Inject(method = "shouldRenderAtSqrDistance", at = @At("HEAD"), cancellable = true)
    private void familyaddons$alwaysRenderHighlighted(double distanceSqr, CallbackInfoReturnable<Boolean> cir) {
        if (DungeonHighlight.INSTANCE.isHighlighted((Entity) (Object) this)) cir.setReturnValue(true);
    }
}
