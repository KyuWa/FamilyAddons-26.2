package org.kyowa.familyaddons.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import org.kyowa.familyaddons.features.PearlWaypoints;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reads Hypixel's progress title (`[some prefix] XX%`) from the incoming
 * set-title packet. 26.2 moved title state out of {@code Gui} (the old
 * {@code Gui#extractTitle}/{@code Gui#title} pair is gone), so the packet
 * handler is the stable hook now. Same main-thread guard as the other packet
 * mixins — handlers run twice per packet (netty thread, then re-scheduled on
 * the main thread). Deduplicates per identical title text so the parse logic
 * fires once per unique title.
 */
@Mixin(ClientPacketListener.class)
public class PearlTitlePacketMixin {

    @Unique private String fa$lastTitleText = "";

    @Inject(method = "setTitleText", at = @At("HEAD"))
    private void familyaddons$onTitle(ClientboundSetTitleTextPacket packet, CallbackInfo ci) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || !mc.isSameThread()) return;
            String raw = packet.text().getString();
            if (raw == null || raw.isEmpty()) return;
            if (raw.equals(fa$lastTitleText)) return;
            fa$lastTitleText = raw;
            PearlWaypoints.INSTANCE.onTitle(raw);
        } catch (Throwable ignored) {
            // Never let this propagate — would break packet handling.
        }
    }
}
