package org.kyowa.familyaddons.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.kyowa.familyaddons.features.CameraHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Routes mouse-look deltas to CameraHelper.freelookYaw/Pitch instead of the
 * local player when freelook is active.
 *
 * Vanilla call chain: MouseHandler.updateMouse → client.player.changeLookDirection(dx, dy).
 * That method does this.yaw += dx*0.15 ; this.pitch += dy*0.15. We intercept
 * at HEAD on Entity (the declaring class), filter to the local player, route
 * the delta to CameraHelper, and cancel so the player doesn't rotate.
 *
 * Mixing onto Entity rather than ClientPlayerEntity because changeLookDirection
 * is declared on Entity. The (Object)this == client.player check filters to
 * only the local player; remote entities never have changeLookDirection called
 * on them anyway, but the check is defensive.
 */
@Mixin(Entity.class)
public class FreelookEntityMixin {

    @Inject(method = "turn(DD)V", at = @At("HEAD"), cancellable = true)
    private void familyaddons$changeLookDirection(double cursorDeltaX, double cursorDeltaY, CallbackInfo ci) {
        if (!CameraHelper.freelookActive) return;
        Minecraft client = Minecraft.getInstance();
        if (client == null || (Object) this != client.player) return;

        CameraHelper.applyMouseDelta(cursorDeltaX, cursorDeltaY);
        ci.cancel();
    }
}
