package org.kyowa.familyaddons.features

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.decoration.ArmorStand
import org.kyowa.familyaddons.COLOR_CODE_REGEX
import org.kyowa.familyaddons.features.pearl.Place

/**
 * Shared scan of "SUPPLIES RECEIVED" armor stands for Kuudra Phase 1.
 *
 * Multiple features (PearlWaypoints, PileWaypoints, ...) need to know which
 * supply piles have already been deposited. Rather than each one doing its
 * own per-tick world entity scan, this object owns one scan and exposes the
 * results.
 *
 * Scan runs every 10 client ticks (~0.5s) when in Kuudra Phase 1, and
 * matches armor stands within 6 blocks of any [Place] location whose name
 * contains "SUPPLIES RECEIVED" (case-insensitive, color codes stripped).
 *
 * Results are cleared:
 *  - On world disconnect/join
 *  - Whenever Phase 1 is not active (so stale data doesn't leak between runs)
 */
object KuudraOccupancy {

    private val occupied: MutableSet<Place> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    private var scanTicker = 0
    private const val SCAN_INTERVAL_TICKS = 10
    private const val MAX_DIST_SQ = 6.0 * 6.0

    /** Read-only view of currently-occupied piles. Safe to call any thread. */
    val occupiedPlaces: Set<Place> get() = occupied

    fun isOccupied(place: Place): Boolean = place in occupied

    fun register() {
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> occupied.clear() }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> occupied.clear() }

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            // Outside Phase 1 → results are stale, clear and skip.
            if (!KuudraPhase.isInP1() || !AutoRequeue.isInKuudra()) {
                if (occupied.isNotEmpty()) occupied.clear()
                return@register
            }

            scanTicker++
            if (scanTicker < SCAN_INTERVAL_TICKS) return@register
            scanTicker = 0
            scan(client)
        }
    }

    private fun scan(client: Minecraft) {
        val world = client.level ?: run { occupied.clear(); return }
        val newOccupied = mutableSetOf<Place>()
        for (entity in world.entitiesForRendering()) {
            if (entity !is ArmorStand) continue
            val nameText = entity.customName ?: entity.name
            val nameStr = nameText.string.replace(COLOR_CODE_REGEX, "").trim()
            if (!nameStr.contains("SUPPLIES RECEIVED", ignoreCase = true)) continue

            val ex = entity.x; val ez = entity.z
            var nearest: Place? = null
            var bestDistSq = MAX_DIST_SQ
            for (place in Place.values()) {
                val dx = place.location.x - ex
                val dz = place.location.z - ez
                val d = dx * dx + dz * dz
                if (d <= bestDistSq) {
                    bestDistSq = d
                    nearest = place
                }
            }
            if (nearest != null) newOccupied.add(nearest)
        }
        occupied.clear()
        occupied.addAll(newOccupied)
    }
}
