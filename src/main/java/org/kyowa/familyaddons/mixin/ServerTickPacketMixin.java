package org.kyowa.familyaddons.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import org.kyowa.familyaddons.features.ServerTickTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hypixel sends ClientboundPingPacket with a NEGATIVE parameter on every server tick.
 * This is the real 20Hz server-tick signal used by Odin/Devonian.
 *
 * We mixin to ClientCommonPacketListenerImpl (the superclass) rather than
 * ClientPlayNetworkHandler, because `onPing` is declared on the superclass —
 * mixin's @Inject cannot target inherited methods on a subclass.
 *
 * IMPORTANT: onPing is called TWICE per packet arrival:
 *   1. First on the netty I/O thread → NetworkThreadUtils.forceMainThread throws
 *      OffThreadException, rescheduling the method on the main thread.
 *   2. Then on the main thread → actually processes the packet.
 *
 * If we injected at HEAD unconditionally, we'd count every tick twice and the
 * timer would run at 2x speed. Devonian's equivalent mixin injects AFTER
 * forceMainThread to side-step this; we achieve the same result by checking
 * the thread ourselves and early-returning on the netty call.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public class ServerTickPacketMixin {

    @Inject(method = "handlePing", at = @At("HEAD"))
    private void familyaddons$onPing(ClientboundPingPacket packet, CallbackInfo ci) {
        // Skip the netty-thread invocation — we only want to count once, on the
        // main-thread re-invocation.
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || !mc.isSameThread()) return;

        // Only count Hypixel's server-tick pings (negative parameter).
        if (packet.getId() >= 0) return;

        ServerTickTracker.INSTANCE.onServerTick();
    }
}
