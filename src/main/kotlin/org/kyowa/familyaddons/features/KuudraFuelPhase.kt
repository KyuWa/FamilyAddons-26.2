package org.kyowa.familyaddons.features

import com.mojang.blaze3d.vertex.PoseStack
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.phys.Vec3
import org.kyowa.familyaddons.COLOR_CODE_REGEX
import org.kyowa.familyaddons.config.FamilyConfigManager
import org.kyowa.familyaddons.features.pearl.PearlCalculator

/**
 * Kuudra "fuel phase" helpers for Basic/Hot (T1/T2), where the fight after
 * the build phase is: fish up fuel cells, carry them to the Ballista.
 *
 *  - Starts on Elle's "Phew! The Ballista is finally ready! ..." line, ends
 *    on KUUDRA DOWN / Elle's "POW!" / the next run start. Tiers 3+ have a
 *    different fight after that line, so the tier is checked at start.
 *  - Ballista pearl waypoint: a pearl aim point (same solver as the supply
 *    pearl waypoints) that lands you on the Ballista in the middle of the
 *    piles, so you can pearl back with a fuel cell from wherever it surfaced.
 *  - Fuel cell waypoints: a beacon beam on every "FUEL CELL" nametag stand.
 */
object KuudraFuelPhase {

    private const val BALLISTA_READY_MSG =
        "[NPC] Elle: Phew! The Ballista is finally ready! It should be strong enough to tank Kuudra's blows now!"
    private const val FIGHT_OVER_MSG =
        "[NPC] Elle: POW! SURELY THAT'S IT! I don't think he has any more in him!"
    private const val RUN_START_MSG =
        "[NPC] Elle: Okay adventurers, I will go and fish up Kuudra!"

    /** Ballista in the middle of the six supply piles (measured in-game). */
    private val BALLISTA = Vec3(-101.5, 79.0, -107.5)

    private const val SCAN_INTERVAL_TICKS = 5
    private const val BEAM_HEIGHT = 80.0

    @Volatile private var inFuelPhase = false
    private var scanTicker = 0

    /** Positions of live FUEL CELL nametag stands, refreshed every few ticks. */
    @Volatile private var fuelCells: List<Vec3> = emptyList()

    private fun cfg() = FamilyConfigManager.config.kuudra

    fun isInFuelPhase() = inFuelPhase

    private fun reset() {
        inFuelPhase = false
        fuelCells = emptyList()
    }

    /** Both features are T1/T2 only. */
    private fun tierAllowed(): Boolean = AutoRequeue.kuudraTierIndex() <= 2

    fun register() {
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> reset() }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> reset() }

        ClientReceiveMessageEvents.ALLOW_GAME.register { message, _ ->
            val plain = message.string.replace(COLOR_CODE_REGEX, "").trim()
            when {
                plain == RUN_START_MSG -> reset()
                plain == BALLISTA_READY_MSG -> if (AutoRequeue.isInKuudra() && tierAllowed()) inFuelPhase = true
                plain == FIGHT_OVER_MSG || plain == "KUUDRA DOWN!" -> reset()
            }
            true
        }

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (!inFuelPhase) return@register
            if (!AutoRequeue.isInKuudra()) { reset(); return@register }
            if (!cfg().fuelCellBeamsEnabled) { if (fuelCells.isNotEmpty()) fuelCells = emptyList(); return@register }
            if (++scanTicker < SCAN_INTERVAL_TICKS) return@register
            scanTicker = 0
            scanFuelCells(client)
        }
    }

    private fun scanFuelCells(client: Minecraft) {
        val world = client.level ?: run { fuelCells = emptyList(); return }
        val found = ArrayList<Vec3>()
        for (entity in world.entitiesForRendering()) {
            if (entity !is ArmorStand || !entity.isAlive) continue
            val name = (entity.customName ?: continue).string.replace(COLOR_CODE_REGEX, "").trim()
            if (!name.equals("FUEL CELL", ignoreCase = true)) continue
            // Critter dump 2026-09-07: the FUEL CELL stand sits ~0.4 above the
            // ground (y 76.4 on y 76 terrain), with a "CLICK TO PICK UP" /
            // "HOOK CLOSER" stand just under it. Start the beam slightly below
            // the stand so it reads as standing on the cell.
            found.add(Vec3(entity.x, entity.y - 0.5, entity.z))
        }
        fuelCells = found
    }

    // ── Render ─────────────────────────────────────────────────────────

    fun hasRender(): Boolean {
        if (!inFuelPhase) return false
        val c = cfg()
        if (!c.fuelPearlEnabled && !c.fuelCellBeamsEnabled) return false
        return Minecraft.getInstance().player != null
    }

    fun onWorldRender(matrices: PoseStack, collector: SubmitNodeCollector, camera: Camera) {
        if (!hasRender()) return
        val c = cfg()
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return

        val cam = camera.position()
        matrices.pushPose()
        matrices.translate(-cam.x, -cam.y, -cam.z)

        if (c.fuelPearlEnabled) {
            val eye = player.getEyePosition(1f)
            // Low arc, same as the main supply pearl waypoints.
            val sol = PearlCalculator.solvePearl(false, eye, eye, BALLISTA)
            if (sol != null) {
                val color = parseColor(c.fuelPearlColor, floatArrayOf(1f, 0.67f, 0f, 1f))
                PearlWaypoints.drawWaypoint(matrices, collector, sol.aimPoint, color, c.fuelPearlSize.toDouble(), c.pearlShape)
            }
        }

        if (c.fuelCellBeamsEnabled) {
            val cells = fuelCells
            if (cells.isNotEmpty()) {
                val color = parseColor(c.fuelCellBeamColor, floatArrayOf(1f, 0.33f, 0.33f, 1f))
                for (cell in cells) {
                    BeaconBeamRenderer.drawBeam(
                        matrices, collector,
                        cell.x, cell.y, cell.z,
                        BEAM_HEIGHT,
                        color[0], color[1], color[2],
                        c.fuelCellBeamOpacity,
                        c.fuelCellBeamWidth,
                    )
                }
            }
        }

        matrices.popPose()
    }

    /** Parse "chroma:alpha:r:g:b" → FloatArray(r, g, b, alpha) all 0..1. */
    private fun parseColor(s: String, fallback: FloatArray): FloatArray {
        return try {
            val p = s.split(":")
            floatArrayOf(
                p[2].toInt() / 255f,
                p[3].toInt() / 255f,
                p[4].toInt() / 255f,
                p[1].toInt() / 255f,
            )
        } catch (e: Exception) { fallback }
    }

    // ── Debug ──────────────────────────────────────────────────────────

    fun debugDump(): String {
        val sb = StringBuilder()
        sb.append("§6[FA Fuel] §7phase=").append(if (inFuelPhase) "§atrue" else "§cfalse")
            .append(" §7tier=§e").append(AutoRequeue.kuudraTierIndex())
            .append(" §7allowed=").append(if (tierAllowed()) "§atrue" else "§cfalse")
            .append(" §7cells=§e").append(fuelCells.size).append("\n")
        for (cell in fuelCells) {
            sb.append("  §7cell @ §f${"%.1f".format(cell.x)}, ${"%.1f".format(cell.y)}, ${"%.1f".format(cell.z)}\n")
        }
        return sb.toString()
    }
}
