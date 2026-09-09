package org.kyowa.familyaddons.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import org.kyowa.familyaddons.features.DungeonHighlight;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Packet-level feed for DungeonHighlight: classify a mob the instant its
 * nametag stand's name arrives (metadata) or it spawns, instead of waiting for
 * the next periodic scan. TAIL = the packet has already been applied to the
 * entity. Both handlers only reach TAIL on the main thread.
 */
@Mixin(ClientPacketListener.class)
public class DungeonEntityPacketMixin {

    @Inject(method = "handleSetEntityData", at = @At("TAIL"))
    private void familyaddons$onEntityData(ClientboundSetEntityDataPacket packet, CallbackInfo ci) {
        if (!Minecraft.getInstance().isSameThread()) return;
        DungeonHighlight.INSTANCE.onEntityUpdate(packet.id());
    }

    @Inject(method = "handleAddEntity", at = @At("TAIL"))
    private void familyaddons$onAddEntity(ClientboundAddEntityPacket packet, CallbackInfo ci) {
        if (!Minecraft.getInstance().isSameThread()) return;
        DungeonHighlight.INSTANCE.onEntityUpdate(packet.getId());
    }
}
