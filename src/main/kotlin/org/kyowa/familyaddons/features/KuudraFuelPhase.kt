package org.kyowa.familyaddons.features

import com.mojang.blaze3d.vertex.PoseStack
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.phys.AABB
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
 *    Carries the same throw timer: the fuel cell pickup shows the same
 *    progress bar as a supply grab, PearlWaypoints tracks it (with its own
 *    learned length, key ".../fuel") and the label counts down to NOW.
 *  - Fuel cell waypoints: the cells surface on the same invisible giants
 *    Hypixel uses for supply crates, so the beam sits on the giant-derived
 *    crate position ([KuudraGiants.cratePosFor]) exactly like the supply
 *    waypoints, and the fuel cell gets the same drag circle and crate hitbox
 *    (Crate Waypoints colours). The "FUEL CELL" nametag stand lags behind
 *    the hooked cell by up to a second, so it is only the fallback when no
 *    giant is tracked.
 */
object KuudraFuelPhase {

    private const val BALLISTA_READY_MSG =
        "[NPC] Elle: Phew! The Ballista is finally ready! It should be strong enough to tank Kuudra's blows now!"
    private const val FIGHT_OVER_MSG =
        "[NPC] Elle: POW! SURELY THAT'S IT! I don't think he has any more in him!"
    private const val RUN_START_MSG =
        "[NPC] Elle: Okay adventurers, I will go and fish up Kuudra!"

    /** Ballista in the middle of the six supply piles (measured in-game). */
    val BALLISTA = Vec3(-101.5, 79.0, -107.5)

    private const val BEAM_HEIGHT = 80.0

    @Volatile private var inFuelPhase = false

    /** Live FUEL CELL nametag stands, refreshed every tick while in the phase. */
    @Volatile private var fuelCells: List<ArmorStand> = emptyList()

    /**
     * Giants confirmed to carry a fuel cell: a FUEL CELL stand was seen within
     * [MATCH_DIST] of the giant's crate position at least once. Sticky by
     * entity id for the giant's lifetime, because the stand lags a hooked
     * cell by up to a second. Other giants in the arena (energized chunks and
     * the like) never get in here, so they get no beam, circle or hitbox.
     */
    private val fuelGiantIds = HashSet<Int>()
    private const val MATCH_DIST = 6.0

    private fun cfg() = FamilyConfigManager.config.kuudra

    fun isInFuelPhase() = inFuelPhase

    private fun reset() {
        inFuelPhase = false
        fuelCells = emptyList()
        fuelGiantIds.clear()
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
            scanFuelCells(client)
        }
    }

    private fun scanFuelCells(client: Minecraft) {
        val world = client.level ?: run { fuelCells = emptyList(); return }
        val found = ArrayList<ArmorStand>()
        for (entity in world.entitiesForRendering()) {
            if (entity !is ArmorStand || !entity.isAlive) continue
            val name = (entity.customName ?: continue).string.replace(COLOR_CODE_REGEX, "").trim()
            if (!name.equals("FUEL CELL", ignoreCase = true)) continue
            found.add(entity)
        }
        fuelCells = found

        // Confirm giants against the stands; forget giants that are gone.
        val alive = HashSet<Int>()
        for (g in KuudraGiants.giants) {
            if (!g.isAlive) continue
            alive.add(g.id)
            if (g.id in fuelGiantIds) continue
            val crate = KuudraGiants.cratePosFor(g)
            if (found.any { st -> Math.hypot(st.x - crate.x, st.z - crate.z) <= MATCH_DIST }) fuelGiantIds.add(g.id)
        }
        fuelGiantIds.retainAll(alive)
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
                if (c.fuelPearlTimer) {
                    // Live countdown while a fuel cell pickup is running, grey flight time otherwise.
                    val label = PearlWaypoints.timerString(sol.flightTimeMs, isDoublePearl = false)
                        ?: "§7${sol.flightTimeMs}ms"
                    PearlWaypoints.drawLabel(matrices, collector, sol.aimPoint, label, c.pearlTimerScale, c.pearlTimerPos)
                }
            }
        }

        if (c.fuelCellBeamsEnabled) {
            val color = parseColor(c.fuelCellBeamColor, floatArrayOf(1f, 0.33f, 0.33f, 1f))
            val giants = KuudraGiants.giants.filter { it.isAlive && it.id in fuelGiantIds }
            if (giants.isNotEmpty()) {
                val dragColor = parseColor(c.dragHitboxColor, floatArrayOf(1f, 1f, 0f, 1f))
                val dragRange = parseColor(c.dragHitboxInRangeColor, floatArrayOf(0f, 1f, 0f, 1f))
                val crateColor = parseColor(c.crateHitboxColor, floatArrayOf(1f, 1f, 0f, 1f))
                val crateReach = parseColor(c.crateHitboxInReachColor, floatArrayOf(0f, 1f, 0f, 1f))
                for (g in giants) {
                    val pos = KuudraGiants.cratePosFor(g)
                    BeaconBeamRenderer.drawBeam(
                        matrices, collector,
                        pos.x, pos.y, pos.z,
                        BEAM_HEIGHT,
                        color[0], color[1], color[2],
                        c.fuelCellBeamOpacity,
                        c.fuelCellBeamWidth,
                    )
                    // Drag circle: turns "in range" when your bobber is close enough to pull it.
                    val dc = if (c.dragHitboxInRangeColorChange && KuudraCrateWaypoints.bobberInDragRange(g)) dragRange else dragColor
                    KuudraCrateWaypoints.drawHorizontalCircle(matrices, collector, pos, KuudraCrateWaypoints.DRAG_DIST, dc)
                }
                // Crate hitbox: the zombie riding next to the cell, green when in reach.
                for (z in KuudraGiants.zombies) {
                    if (!z.isAlive) continue
                    val zPos = Vec3(z.x, z.y, z.z)
                    val near = giants.any { KuudraGiants.cratePosFor(it).distanceTo(zPos) <= KuudraCrateWaypoints.ZOMBIE_TO_CRATE_MAX }
                    if (!near) continue
                    val cc = if (c.crateHitboxReachColorChange && KuudraCrateWaypoints.reachDistanceTo(z) <= KuudraCrateWaypoints.REACH_DIST) crateReach else crateColor
                    KuudraCrateWaypoints.drawWireframeBox(matrices, collector, z.boundingBox.inflate(0.1) as AABB, cc)
                }
            }
            val cells = fuelCells
            if (giants.isEmpty() && cells.isNotEmpty()) {
                val partial = mc.deltaTracker.getGameTimeDeltaPartialTick(true)
                for (cell in cells) {
                    if (!cell.isAlive) continue
                    // Critter dump 2026-09-07: the FUEL CELL stand sits ~0.4 above the
                    // ground with a "CLICK TO PICK UP" / "HOOK CLOSER" stand just under
                    // it. Start the beam slightly below so it reads as standing on the cell.
                    val pos = cell.getPosition(partial)
                    BeaconBeamRenderer.drawBeam(
                        matrices, collector,
                        pos.x, pos.y - 0.5, pos.z,
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
            .append(" §7cells=§e").append(fuelCells.size)
            .append(" §7fuelGiants=§e").append(fuelGiantIds.size)
            .append(" §7giants=§e").append(KuudraGiants.giants.size)
            .append(" §7zombies=§e").append(KuudraGiants.zombies.size).append("\n")
        for (cell in fuelCells) {
            sb.append("  §7cell @ §f${"%.1f".format(cell.x)}, ${"%.1f".format(cell.y)}, ${"%.1f".format(cell.z)}\n")
        }
        return sb.toString()
    }
}
