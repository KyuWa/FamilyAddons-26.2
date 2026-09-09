package org.kyowa.familyaddons.util

import net.hypixel.modapi.HypixelModAPI
import net.hypixel.modapi.packet.impl.clientbound.event.ClientboundLocationPacket

/**
 * The only class that touches the Hypixel Mod API types. It is loaded solely
 * from [HypixelLocation.register], and only when the hypixel-mod-api mod is
 * present, so a client without that mod never resolves these classes.
 */
object HypixelApiBridge {
    fun init() {
        val api = HypixelModAPI.getInstance()
        api.subscribeToEventPacket(ClientboundLocationPacket::class.java)
        api.registerHandler(ClientboundLocationPacket::class.java) { packet ->
            HypixelLocation.map = packet.map.orElse(null)
            HypixelLocation.mode = packet.mode.orElse(null)
        }
    }
}
