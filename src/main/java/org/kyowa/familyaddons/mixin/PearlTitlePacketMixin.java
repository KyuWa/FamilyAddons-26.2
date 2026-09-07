package org.kyowa.familyaddons.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import org.kyowa.familyaddons.features.PearlWaypoints;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feeds the Kuudra grab progress text ("[prefix] XX%") to PearlWaypoints.
 *
 * 26.2 keeps title state out of Gui, so the packet handlers are the hook:
 * title, subtitle and action bar are all read, wherever the server puts the
 * bar. Same main-thread guard as the other packet mixins (handlers run on
 * the netty thread first, then again on the main thread). Deduplicated per
 * identical text so the parser fires once per distinct message.
 */
@Mixin(ClientPacketListener.class)
public class PearlTitlePacketMixin {

    @Unique private String fa$lastText = "";

    @Unique
    private void fa$feed(Component c) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || !mc.isSameThread()) return;
            if (c == null) return;
            String raw = c.getString();
            if (raw == null || raw.isEmpty() || raw.equals(fa$lastText)) return;
            fa$lastText = raw;
            PearlWaypoints.INSTANCE.onTitle(raw);
        } catch (Throwable ignored) {
            // Never let this propagate; it would break packet handling.
        }
    }

    @Inject(method = "setTitleText", at = @At("HEAD"))
    private void familyaddons$onTitle(ClientboundSetTitleTextPacket packet, CallbackInfo ci) { fa$feed(packet.text()); }

    @Inject(method = "setSubtitleText", at = @At("HEAD"))
    private void familyaddons$onSubtitle(ClientboundSetSubtitleTextPacket packet, CallbackInfo ci) { fa$feed(packet.text()); }

    @Inject(method = "setActionBarText", at = @At("HEAD"))
    private void familyaddons$onActionBar(ClientboundSetActionBarTextPacket packet, CallbackInfo ci) { fa$feed(packet.text()); }
}
