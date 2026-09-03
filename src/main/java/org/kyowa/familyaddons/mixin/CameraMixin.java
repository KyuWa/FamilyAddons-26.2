package org.kyowa.familyaddons.mixin;

import net.minecraft.client.Camera;
import org.kyowa.familyaddons.features.CameraHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Three patches on net.minecraft.client.render.Camera:
 *
 *  1. clipToSpace(F) — when CameraClip is on, return the unclipped distance
 *     so the camera passes through walls.
 *
 *  2. The argument to clipToSpace inside update() — swap in the user's custom
 *     distance via @ModifyArg.
 *
 *  3. update() — apply freelook BEFORE moveBy() runs. This is the key detail:
 *     moveBy uses this.rotation (the Quaternionf) to figure out which way is
 *     "back" when shifting the camera away from the player. If we override
 *     rotation BEFORE moveBy, the camera ends up at playerEyes + (-freelookForward
 *     * distance) — i.e. orbiting the player using the freelook angle, with
 *     the player centered in view. This matches Odin's behavior.
 *
 *     We inject at the call to clipToSpace inside the third-person block. By
 *     that point setPos() has placed us at the player's eyes; we then overwrite
 *     rotation, and the immediately-following moveBy(-clipToSpace(...)) shifts
 *     the camera "back" along the freelook direction.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {

    @Shadow protected abstract void setRotation(float yaw, float pitch);

    @Inject(method = "getMaxZoom", at = @At("HEAD"), cancellable = true)
    private void familyaddons$clipToSpace(float distance, CallbackInfoReturnable<Float> cir) {
        if (CameraHelper.isClipEnabled()) {
            cir.setReturnValue(distance);
        }
    }

    @ModifyArg(
            method = "alignWithEntity(F)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/Camera;getMaxZoom(F)F"
            ),
            require = 1
    )
    private float familyaddons$customDistance(float original) {
        Float custom = CameraHelper.getCustomDistance();
        return custom != null ? custom : original;
    }

    /**
     * Override rotation right before clipToSpace + moveBy run. Position is at
     * the player's eyes at this point; setting rotation now means moveBy will
     * shift the camera back along the freelook direction, orbiting the player.
     *
     * shift = -1 puts the inject point immediately BEFORE the clipToSpace
     * INVOKE, so our setRotation call is the last thing before vanilla
     * computes the camera offset.
     */
    @Inject(
            method = "alignWithEntity(F)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/Camera;getMaxZoom(F)F",
                    shift = At.Shift.BEFORE
            ),
            require = 1
    )
    private void familyaddons$applyFreelookBeforeMove(CallbackInfo ci) {
        if (!CameraHelper.freelookActive) return;
        setRotation(CameraHelper.freelookYaw, CameraHelper.freelookPitch);
    }
}