package org.kyowa.familyaddons.features

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.monster.Giant
import net.minecraft.world.entity.monster.zombie.Zombie
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.sin

/**
 * Shared giant + zombie entity scan for Kuudra Phase 1.
 *
 * Multiple features need this data:
 *   - KuudraCrateWaypoints (whitelisted)  — drag radius + crate hitbox
 *   - SupplyWaypoints      (public)        — beacon beams over crates
 *
 * Each one used to scan independently, but that would mean public users
 * couldn't see SupplyWaypoints unless KuudraCrateWaypoints (whitelist-gated)
 * was also active. Pulling the scan out here lets either feature work
 * independently, and avoids two scans of `world.entitiesForRendering()` per tick.
 *
 * Crate position math is also exposed here ([cratePosFor]) so both consumers
 * use the exact same PawsUp-derived formula:
 *   x = giant.x + 2.7 * cos((yaw + 130°) * π/180)
 *   z = giant.z + 5.2 * sin((yaw + 130°) * π/180)
 *   y = 75.5 (literal world Y, independent of the giant's Y)
 */
object KuudraGiants {

    private const val MIN_Y = 60.0
    private const val MAX_Y = 78.0

    // Crate position constants — kept here so both features share one source.
    private const val CRATE_YAW_BIAS_DEG = 130.0
    private const val CRATE_X_RADIUS     = 2.7
    private const val CRATE_Z_RADIUS     = 5.2
    private const val CRATE_WORLD_Y      = 75.5

    private val _giants = LinkedHashSet<Giant>()
    private val _zombies = LinkedHashSet<Zombie>()

    /** Currently-tracked giants. Read-only view; safe to iterate. */
    val giants: Set<Giant> get() = _giants

    /** Currently-tracked zombies. Used by KuudraCrateWaypoints for hitbox rendering. */
    val zombies: Set<Zombie> get() = _zombies

    /**
     * Compute crate world position from a giant's position + yaw.
     * Same formula as PawsUp's published values.
     */
    fun cratePosFor(g: Giant): Vec3 {
        val angleRad = Math.toRadians(g.yRot + CRATE_YAW_BIAS_DEG)
        return Vec3(
            g.x + CRATE_X_RADIUS * cos(angleRad),
            CRATE_WORLD_Y,
            g.z + CRATE_Z_RADIUS * sin(angleRad),
        )
    }

    /** All current crate positions, one per detected giant. */
    fun getCratePositions(): List<Vec3> = _giants.map { cratePosFor(it) }

    /** True if either consumer feature is currently active. Drives whether scan runs. */
    private fun shouldScan(): Boolean {
        val cfg = org.kyowa.familyaddons.config.FamilyConfigManager.config
        // Public feature — anyone can enable.
        if (cfg.kuudra.supplyWaypointsEnabled) return true
        if (cfg.kuudra.crateWaypointsEnabled) return true
        return false
    }

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register {
            // Stop scanning when no consumer feature wants the data, or when
            // we're outside Phase 1.
            if (!shouldScan() || !AutoRequeue.isInKuudra() || !KuudraPhase.isInP1()) {
                if (_giants.isNotEmpty() || _zombies.isNotEmpty()) {
                    _giants.clear(); _zombies.clear()
                }
                return@register
            }
            scan()
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            _giants.clear(); _zombies.clear()
        }
        ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
            _giants.clear(); _zombies.clear()
        }
    }

    private fun scan() {
        val world = Minecraft.getInstance().level ?: return
        _giants.removeIf { !it.isAlive || it.y < MIN_Y || it.y > MAX_Y }
        _zombies.removeIf { !it.isAlive || it.y < MIN_Y || it.y > MAX_Y }
        for (entity in world.entitiesForRendering()) {
            if (!entity.isAlive) continue
            val y = entity.y
            if (y < MIN_Y || y > MAX_Y) continue
            when (entity) {
                is Giant  -> _giants.add(entity)
                is Zombie -> _zombies.add(entity)
                else -> {}
            }
        }
    }
}
