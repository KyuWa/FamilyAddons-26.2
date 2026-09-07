package org.kyowa.familyaddons.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import org.kyowa.familyaddons.features.NoDebuff;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * No Blindness / No Nausea: drop the effect packet for the local player so the
 * effect is never applied client-side. Like the other packet mixins, the
 * handler is invoked twice per packet (netty thread, then main thread); we
 * only decide on the main-thread call, where the player entity is safe to read.
 */
@Mixin(ClientPacketListener.class)
public class MobEffectPacketMixin {

    @Inject(method = "handleUpdateMobEffect", at = @At("HEAD"), cancellable = true)
    private void familyaddons$onUpdateMobEffect(ClientboundUpdateMobEffectPacket packet, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || !mc.isSameThread()) return;
        if (NoDebuff.INSTANCE.shouldBlock(packet.getEntityId(), packet.getEffect())) ci.cancel();
    }
}
