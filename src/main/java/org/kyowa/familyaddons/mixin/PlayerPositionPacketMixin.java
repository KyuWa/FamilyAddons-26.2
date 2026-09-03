package org.kyowa.familyaddons.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.world.phys.Vec3;
import org.kyowa.familyaddons.features.KuudraDirection;
import org.kyowa.familyaddons.features.KuudraStunWaypoint;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Server-teleport hook for Kuudra features. Like ServerTickPacketMixin, the
 * handler is invoked twice per packet (netty thread, then re-scheduled on the
 * main thread) — we only act on the main-thread call.
 *
 * Consumers:
 *  - KuudraDirection: teleport to y == 6 means the player got eaten; freeze
 *    the last direction callout for a second.
 *  - KuudraStunWaypoint: teleport to the belly entry point switches the pod
 *    waypoint from offset-relative to absolute coords.
 */
@Mixin(ClientPacketListener.class)
public class PlayerPositionPacketMixin {

    @Inject(method = "handleMovePlayer", at = @At("HEAD"))
    private void familyaddons$onMovePlayer(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || !mc.isSameThread()) return;

        Vec3 pos = packet.change().position();
        KuudraDirection.INSTANCE.onTeleport(pos);
        KuudraStunWaypoint.INSTANCE.onTeleport(pos);
    }
}
