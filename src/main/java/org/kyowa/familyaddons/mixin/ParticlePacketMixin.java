package org.kyowa.familyaddons.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import org.kyowa.familyaddons.features.SparklingCritterHighlight;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Particle hook for sparkle detection (SparklingCritterHighlight). Same
 * main-thread guard as the other packet mixins — handlers run twice per
 * packet (netty thread, then re-scheduled on the main thread).
 */
@Mixin(ClientPacketListener.class)
public class ParticlePacketMixin {

    @Inject(method = "handleParticleEvent", at = @At("HEAD"))
    private void familyaddons$onParticles(ClientboundLevelParticlesPacket packet, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || !mc.isSameThread()) return;
        SparklingCritterHighlight.INSTANCE.onParticle(packet);
    }
}
