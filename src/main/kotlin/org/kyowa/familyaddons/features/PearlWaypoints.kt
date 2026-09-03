package org.kyowa.familyaddons.features

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.Camera
import net.minecraft.client.renderer.Lightmap
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.SubmitNodeCollector
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.kyowa.familyaddons.COLOR_CODE_REGEX
import org.kyowa.familyaddons.config.FamilyConfigManager
import org.kyowa.familyaddons.features.pearl.DoublePearls
import org.kyowa.familyaddons.features.pearl.MissingSupplies
import org.kyowa.familyaddons.features.pearl.PearlCalculator
import org.kyowa.familyaddons.features.pearl.Place
import org.kyowa.familyaddons.features.pearl.Pre
import org.kyowa.familyaddons.features.pearl.Prio
import org.lwjgl.opengl.GL11
import kotlin.math.sqrt

/**
 * PawsUp-style dynamic Pearl Waypoints — Phase E.
 *
 * Adds on top of Phase B/C/D:
 *  - "NOW sound" — play a single ping the first frame the throw window opens.
 *    Re-armed at each grab start.
 *  - Occupancy fallback — if the destination pile for the player's current
 *    Pre is already occupied, render waypoints to ALL OTHER unoccupied piles
 *    so the player can deposit somewhere else.
 *  - Expanded debug — /fapearl info now lists which double-pearl routes are
 *    in range and why they might be hidden (occupancy, missing supplies).
 */
object PearlWaypoints {

    // ── Chat parsing ───────────────────────────────────────────────────

    private val MISSING_REGEX = Regex(
        """^Party > (?:\[[^]]*?])? ?(\w{1,16}): No ?(Triangle|X|Equals|Slash|X Cannon|xCannon|Square|Shop)!$""",
        RegexOption.IGNORE_CASE
    )

    private val PROGRESS_REGEX = Regex("""\[.*?]\s*(\d+)%""")

    private val GRAB_LOSS_LINES = listOf(
        "You moved and the Chest slipped out of your hands!",
        "You retrieved some of Elle's supplies from the Lava!",
    )

    // ── State ──────────────────────────────────────────────────────────

    @Volatile private var grabbing: Boolean = false
    @Volatile private var grabStartTick: Int = -1
    @Volatile private var tickCount: Int = 0

    /** Set true after the NOW sound has fired for the current grab. Reset on grab start. */
    @Volatile private var nowSoundPlayed: Boolean = false

    // Occupancy ("SUPPLIES RECEIVED" stands) is owned by KuudraOccupancy now
    // and shared across PearlWaypoints + PileWaypoints. Read from
    // KuudraOccupancy.occupiedPlaces wherever needed.

    // ── PawsUp's pickTimings table ─────────────────────────────────────
    // [talisman 0..3 = NoTali..T3][kuudraTier-1 0..4 = T1..T5] → ticks
    private val pickTimings: Array<IntArray> = arrayOf(
        intArrayOf(60, 80, 100, 120, 120),  // No Tali
        intArrayOf(55, 75,  90, 110, 110),  // T1
        intArrayOf(50, 65,  80, 100, 100),  // T2
        intArrayOf(45, 60,  70,  85,  85),  // T3
    )

    // ── Public API ─────────────────────────────────────────────────────

    fun hasWaypoints(): Boolean {
        if (!FamilyConfigManager.config.kuudra.pearlWaypointsEnabled) return false
        if (!AutoRequeue.isInKuudra()) return false
        if (!KuudraPhase.isInP1()) return false
        return true
    }

    fun onTitle(rawTitle: String) {
        val plain = rawTitle.replace(COLOR_CODE_REGEX, "")
        val match = PROGRESS_REGEX.find(plain) ?: return
        val pct = match.groupValues[1].toIntOrNull() ?: return
        when {
            pct == 0 -> {
                grabbing = true
                grabStartTick = tickCount
                nowSoundPlayed = false
            }
            grabbing && pct >= 100 -> clearGrab()
        }
    }

    private fun clearGrab() {
        grabbing = false
        grabStartTick = -1
        tickCount = 0
        nowSoundPlayed = false
    }

    fun register() {
        ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
            MissingSupplies.clear()
            clearGrab()
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            MissingSupplies.clear()
            clearGrab()
        }

        ClientReceiveMessageEvents.ALLOW_GAME.register { message, _ ->
            handleChat(message.string.replace(COLOR_CODE_REGEX, "").trim())
            true
        }

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            Prio.useNewPrio = FamilyConfigManager.config.kuudra.pearlNewPrio

            if (!KuudraPhase.isInP1()) {
                if (grabbing) clearGrab()
                if (MissingSupplies.missing.isNotEmpty()) MissingSupplies.clear()
            }

            if (grabbing) tickCount++

            // Check NOW sound trigger every tick during a grab.
            if (grabbing && !nowSoundPlayed) {
                if (shouldFireNowSound()) {
                    playNowSound()
                    nowSoundPlayed = true
                }
            }

            // Occupancy scanning is handled by KuudraOccupancy.register().
        }
    }

    private fun handleChat(plain: String) {
        when {
            plain in GRAB_LOSS_LINES -> clearGrab()
            else -> {
                val m = MISSING_REGEX.find(plain) ?: return
                val name = m.groupValues[2]
                val place = parsePlaceName(name) ?: return
                MissingSupplies.missing.add(place)
            }
        }
    }

    private fun parsePlaceName(s: String): Place? = when (s.lowercase()) {
        "shop"     -> Place.SHOP
        "x"        -> Place.X
        "x cannon", "xcannon" -> Place.X_CANNON
        "equals"   -> Place.EQUALS
        "slash"    -> Place.SLASH
        "triangle" -> Place.TRIANGLE
        else -> null
    }

    // scanOccupancy() moved to KuudraOccupancy.scan() — shared with PileWaypoints.

    private fun preToPlace(pre: Pre): Place? {
        val target = Prio.getSupplyForSpot(pre) ?: return null
        for (place in Place.values()) {
            if (place.location == target) return place
        }
        return null
    }

    private fun getMaxTimeMs(): Long {
        val kuudra = AutoRequeue.kuudraTierIndex()
        if (kuudra == 0) return 6000L
        val tierIdx = (kuudra - 1).coerceIn(0, 4)
        val taliIdx = FamilyConfigManager.config.kuudra.pearlTalismanTier.coerceIn(0, 3)
        return pickTimings[taliIdx][tierIdx] * 50L
    }

    /**
     * True if the throw window for the player's current Pre is open (or past).
     * Uses the MAIN waypoint (not double-pearl) for the trigger.
     */
    private fun shouldFireNowSound(): Boolean {
        if (!grabbing || grabStartTick < 0) return false
        val cfg = FamilyConfigManager.config.kuudra
        if (!cfg.pearlNowSound) return false

        val mc = Minecraft.getInstance()
        val player = mc.player ?: return false
        val eye = player.getEyePosition(1f)
        val pre = Pre.getClosestSpot(eye)
        if (pre == Pre.NONE) return false

        val supplyDest = Prio.getSupplyForSpot(pre) ?: return false
        val sol = PearlCalculator.solvePearl(false, eye, eye, supplyDest) ?: return false

        val ticksSinceGrab = (tickCount - grabStartTick).coerceAtLeast(0)
        val flightTicks = (sol.flightTimeMs / 50L).toInt()
        val maxTicks = (getMaxTimeMs() / 50L).toInt()
        val delayTicks = (cfg.pearlTimerDelay.toLong() / 50L).toInt()
        val remaining = maxTicks - ticksSinceGrab - flightTicks + delayTicks
        return remaining <= 0
    }

    private fun playNowSound() {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val volume = FamilyConfigManager.config.kuudra.pearlNowSoundVolume.coerceIn(0f, 2f)
        if (volume <= 0f) return
        // Use a high-pitched note block for clear, distinguishable feedback.
        player.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), volume, 1.8f)
    }

    private fun timerString(flightTimeMs: Long, isDoublePearl: Boolean): String? {
        if (!grabbing || grabStartTick < 0) return null
        val cfg = FamilyConfigManager.config.kuudra

        val ticksSinceGrab = (tickCount - grabStartTick).coerceAtLeast(0)
        val flightTicks = (flightTimeMs / 50L).toInt()
        val maxTicks = (getMaxTimeMs() / 50L).toInt()
        val delayTicks = (cfg.pearlTimerDelay.toLong() / 50L).toInt()

        val remaining = if (isDoublePearl) {
            val dDelayTicks = (cfg.pearlDPearlLandDelay.toLong() / 50L).toInt()
            (maxTicks + dDelayTicks) - ticksSinceGrab - flightTicks + delayTicks
        } else {
            maxTicks - ticksSinceGrab - flightTicks + delayTicks
        }

        val remainingMs = remaining * 50
        return when {
            remainingMs <= 0   -> "§aNOW"
            remainingMs <= 500 -> "§c${remainingMs}ms"
            remainingMs <= 1000 -> "§e${remainingMs}ms"
            else                -> "§f${remainingMs}ms"
        }
    }

    private fun parseColor(s: String, fallback: FloatArray = floatArrayOf(0.5f, 0.8f, 1f, 1f)): FloatArray {
        return try {
            val p = s.split(":")
            floatArrayOf(
                p[2].toInt() / 255f,
                p[3].toInt() / 255f,
                p[4].toInt() / 255f,
                p[1].toInt() / 255f
            )
        } catch (e: Exception) { fallback }
    }

    private fun yOffsetFor(pre: Pre): Double {
        val cfg = FamilyConfigManager.config.kuudra
        if (!cfg.pearlOffsetsEnabled) return 0.0
        return when (pre) {
            Pre.SHOP     -> cfg.pearlShopOff.toDouble()
            Pre.X        -> cfg.pearlXOff.toDouble()
            Pre.X_CANNON -> cfg.pearlXCannonOff.toDouble()
            Pre.EQUALS   -> cfg.pearlEqualsOff.toDouble()
            Pre.SLASH    -> cfg.pearlSlashOff.toDouble()
            Pre.TRIANGLE -> cfg.pearlTriangleOff.toDouble()
            Pre.SQUARE   -> cfg.pearlSquareOff.toDouble()
            Pre.NONE     -> 0.0
        }
    }

    /**
     * PawsUp's sky-marker render gate: only X_CANNON@X_CANNON, SHOP@SHOP, and
     * TRIANGLE@SHOP (with newPrio) get high-arc waypoints.
     */
    private fun shouldRenderSkyMarker(place: Place, pre: Pre, useNewPrio: Boolean): Boolean {
        if (place == Place.X_CANNON && pre == Pre.X_CANNON) return true
        if (place == Place.SHOP     && pre == Pre.SHOP)     return true
        if (place == Place.TRIANGLE && pre == Pre.SHOP && useNewPrio) return true
        return false
    }

    /**
     * For occupancy fallback: returns the list of Places the player could
     * still pearl to. Excludes occupied Places and (optionally) Places marked
     * missing in chat.
     */
    private fun availableFallbackPlaces(): List<Place> {
        val cfg = FamilyConfigManager.config.kuudra
        return Place.values().filter { p ->
            p !in KuudraOccupancy.occupiedPlaces &&
                    (!cfg.pearlHideOnMissing || p !in MissingSupplies.missing)
        }
    }

    // ── Render ─────────────────────────────────────────────────────────

    fun onWorldRender(matrices: PoseStack, collector: SubmitNodeCollector, camera: Camera) {
        if (!hasWaypoints()) return
        val cfg = FamilyConfigManager.config.kuudra
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return

        val eye = player.getEyePosition(1f)
        val pre = Pre.getClosestSpot(eye)
        if (pre == Pre.NONE) return

        val spawnPos = eye

        val color = parseColor(cfg.pearlColor)
        val dColor = parseColor(cfg.pearlDPearlColor, floatArrayOf(1f, 0.78f, 0.31f, 1f))
        val skyColor = parseColor(cfg.pearlSkyColor, floatArrayOf(0.78f, 1f, 0.31f, 1f))

        val cam = camera.position()
        matrices.pushPose()
        matrices.translate(-cam.x, -cam.y, -cam.z)

        // ── Main waypoint OR fallback to all-other-piles ───────────────
        val mainPlace = preToPlace(pre)
        val supplyDest = Prio.getSupplyForSpot(pre)
        val mainHidden = mainPlace != null && mainPlace in KuudraOccupancy.occupiedPlaces

        // SQUARE special-case: Prio.getSupplyForSpot(SQUARE) returns the first
        // missing supply. If no one has called "No X!" in chat, supplyDest is
        // null. In that case, fall through to the all-other-piles fallback so
        // the SQUARE area still gives the player something to aim at.
        val squareNoTarget = (pre == Pre.SQUARE && supplyDest == null)

        if (supplyDest != null && !mainHidden) {
            // Normal path: render the single waypoint to our designated supply.
            val adjusted = Vec3(supplyDest.x, supplyDest.y + yOffsetFor(pre), supplyDest.z)
            val sol = PearlCalculator.solvePearl(false, eye, spawnPos, adjusted)
            if (sol != null) {
                drawWaypoint(matrices, collector, sol.aimPoint, color, cfg.pearlSize.toDouble(), cfg.pearlShape)
                if (cfg.pearlWaypointTimer) {
                    val label = timerString(sol.flightTimeMs, isDoublePearl = false)
                        ?: "§7${sol.flightTimeMs}ms"
                    drawLabel(matrices, collector, sol.aimPoint, label, cfg.pearlTimerScale, cfg.pearlTimerPos)
                }
            }
        } else if (mainHidden || squareNoTarget) {
            // Fallback: either this pre's designated pile is occupied, or we're
            // in SQUARE with no missing-supply target. Render waypoints to every
            // unoccupied + unmissing pile so the player can deposit somewhere.
            for (place in availableFallbackPlaces()) {
                if (place == mainPlace) continue   // already known to be occupied
                val sol = PearlCalculator.solvePearl(false, eye, spawnPos, place.location) ?: continue
                drawWaypoint(matrices, collector, sol.aimPoint, color, cfg.pearlSize.toDouble(), cfg.pearlShape)
                if (cfg.pearlWaypointTimer) {
                    val label = timerString(sol.flightTimeMs, isDoublePearl = false)
                        ?: "§7${sol.flightTimeMs}ms"
                    drawLabel(matrices, collector, sol.aimPoint, label, cfg.pearlTimerScale, cfg.pearlTimerPos)
                }
            }
        }

        // ── Sky marker — restricted to PawsUp's 3 cases, only when main path is active ──
        if (cfg.pearlSkyPearls && !mainHidden && mainPlace != null && supplyDest != null
            && shouldRenderSkyMarker(mainPlace, pre, FamilyConfigManager.config.kuudra.pearlNewPrio)) {
            val adjusted = Vec3(supplyDest.x, supplyDest.y + yOffsetFor(pre), supplyDest.z)
            val sky = PearlCalculator.solvePearl(true, eye, spawnPos, adjusted)
            if (sky != null) {
                drawWaypoint(matrices, collector, sky.aimPoint, skyColor, cfg.pearlSkySize.toDouble(), cfg.pearlShape)
            }
        }

        // ── Double pearls — fixed handoff routes ───────────────────────
        // PawsUp's logic: for each route whose `pre` matches the player's current
        // Pre area, run a HIGH-ARC pearl solve from eye → dp.location, and draw
        // the waypoint at the SOLVER'S AIM POINT (a point along the player's
        // required look direction), not at the literal handoff coordinate.
        // Drawing at dp.location directly puts the box in the ground / behind
        // walls / out of view depending on player position.
        if (cfg.pearlDPearls) {
            for (dp in DoublePearls.dPearls.values) {
                if (dp.pre != pre) continue

                val destPlace = preToPlace(dp.drop)
                if (destPlace != null && destPlace in KuudraOccupancy.occupiedPlaces) continue
                if (cfg.pearlHideOnMissing && destPlace != null && destPlace in MissingSupplies.missing) continue

                // High-arc solve to the mid-air handoff coordinate.
                val sol = PearlCalculator.solvePearl(true, eye, spawnPos, dp.location) ?: continue
                drawWaypoint(matrices, collector, sol.aimPoint, dColor, cfg.pearlDPearlSize.toDouble(), cfg.pearlShape)

                if (cfg.pearlDPearlTimer) {
                    // Mirror main-waypoint behavior: live timer when grabbing,
                    // static gray flight-time hint when idle.
                    val label = timerString(sol.flightTimeMs, isDoublePearl = true)
                        ?: "§7${sol.flightTimeMs}ms"
                    drawLabel(matrices, collector, sol.aimPoint, label, cfg.pearlDPearlTimerSize, cfg.pearlTimerPos)
                }
            }
        }

        matrices.popPose()
    }

    // ── Shape drawing ──────────────────────────────────────────────────

    private fun drawWaypoint(
        matrices: PoseStack,
        collector: SubmitNodeCollector,
        pos: Vec3,
        color: FloatArray,
        size: Double,
        shape: Int,
    ) {
        val r = color[0]; val g = color[1]; val b = color[2]; val a = color[3]
        val half = size.coerceAtLeast(0.05) / 2.0
        when (shape) {
            0, 1 -> {
                val box = AABB(
                    pos.x - half, pos.y - half, pos.z - half,
                    pos.x + half, pos.y + half, pos.z + half
                )
                collector.submitCustomGeometry(matrices, FamilyRenderTypes.LINES) { pose, buf ->
                    boxEdges(buf, pose, box, r, g, b, a)
                }
                collector.submitCustomGeometry(matrices, FamilyRenderTypes.LINES_NO_DEPTH) { pose, buf ->
                    boxEdges(buf, pose, box, r, g, b, a * 0.3f)
                }
            }
            2 -> drawHorizontalSquare(matrices, collector, pos, half, color)
            3 -> drawHorizontalCircle(matrices, collector, pos, half, color)
        }
    }

    private fun drawHorizontalSquare(
        matrices: PoseStack,
        collector: SubmitNodeCollector,
        center: Vec3,
        half: Double,
        color: FloatArray,
    ) {
        val r = color[0]; val g = color[1]; val b = color[2]; val a = color[3]
        val cx = center.x.toFloat(); val cy = center.y.toFloat(); val cz = center.z.toFloat()
        val h = half.toFloat()
        fun emit(renderType: RenderType, alpha: Float) {
            collector.submitCustomGeometry(matrices, renderType) { pose, buf ->
                val pts = arrayOf(
                    Pair(cx - h, cz - h),
                    Pair(cx + h, cz - h),
                    Pair(cx + h, cz + h),
                    Pair(cx - h, cz + h),
                    Pair(cx - h, cz - h),
                )
                for (i in 0 until pts.size - 1) {
                    val (x0, z0) = pts[i]
                    val (x1, z1) = pts[i + 1]
                    val dx = x1 - x0; val dz = z1 - z0
                    val len = sqrt((dx * dx + dz * dz).toDouble()).toFloat().coerceAtLeast(1e-4f)
                    buf.addVertex(pose, x0, cy, z0).setColor(r, g, b, alpha).setNormal(pose, dx / len, 0f, dz / len)
                    buf.addVertex(pose, x1, cy, z1).setColor(r, g, b, alpha).setNormal(pose, dx / len, 0f, dz / len)
                }
            }
        }
        emit(FamilyRenderTypes.LINES, a)
        emit(FamilyRenderTypes.LINES_NO_DEPTH, a * 0.3f)
    }

    private fun drawHorizontalCircle(
        matrices: PoseStack,
        collector: SubmitNodeCollector,
        center: Vec3,
        radius: Double,
        color: FloatArray,
    ) {
        val r = color[0]; val g = color[1]; val b = color[2]; val a = color[3]
        val cx = center.x.toFloat(); val cy = center.y.toFloat(); val cz = center.z.toFloat()
        val segments = 32
        fun emit(renderType: RenderType, alpha: Float) {
            collector.submitCustomGeometry(matrices, renderType) { pose, buf ->
                val twoPi = (Math.PI * 2.0).toFloat()
                var prevX = (cx + radius).toFloat()
                var prevZ = cz
                for (i in 1..segments) {
                    val angle = twoPi * i / segments
                    val nx = (cx + radius * Math.cos(angle.toDouble())).toFloat()
                    val nz = (cz + radius * Math.sin(angle.toDouble())).toFloat()
                    val dx = nx - prevX; val dz = nz - prevZ
                    val len = sqrt((dx * dx + dz * dz).toDouble()).toFloat().coerceAtLeast(1e-4f)
                    buf.addVertex(pose, prevX, cy, prevZ).setColor(r, g, b, alpha).setNormal(pose, dx / len, 0f, dz / len)
                    buf.addVertex(pose, nx, cy, nz).setColor(r, g, b, alpha).setNormal(pose, dx / len, 0f, dz / len)
                    prevX = nx; prevZ = nz
                }
            }
        }
        emit(FamilyRenderTypes.LINES, a)
        emit(FamilyRenderTypes.LINES_NO_DEPTH, a * 0.3f)
    }

    private fun drawLabel(
        matrices: PoseStack,
        collector: SubmitNodeCollector,
        aimPoint: Vec3,
        text: String,
        scale: Float,
        position: Int,
    ) {
        val mc = Minecraft.getInstance()
        val tr = mc.font

        val yOff = when (position) {
            0 -> 0.7
            1 -> -0.7
            else -> 0.0
        }

        val baseScale = 0.025f * scale.coerceIn(0.1f, 10f)

        matrices.pushPose()
        matrices.translate(aimPoint.x, aimPoint.y + yOff, aimPoint.z)
        matrices.mulPose(mc.gameRenderer.mainCamera().rotation())
        matrices.scale(baseScale, -baseScale, baseScale)

        val w = tr.width(text.replace(COLOR_CODE_REGEX, ""))
        collector.submitText(
            matrices, -w / 2f, 0f,
            Component.literal(text).visualOrderText, true,
            net.minecraft.client.gui.Font.DisplayMode.SEE_THROUGH,
            15728880, -1, 0, 0
        )
        matrices.popPose()
    }

    private fun boxEdges(
        buf: com.mojang.blaze3d.vertex.VertexConsumer,
        entry: com.mojang.blaze3d.vertex.PoseStack.Pose,
        box: AABB,
        r: Float, g: Float, b: Float, a: Float
    ) {
        val x1 = box.minX.toFloat(); val y1 = box.minY.toFloat(); val z1 = box.minZ.toFloat()
        val x2 = box.maxX.toFloat(); val y2 = box.maxY.toFloat(); val z2 = box.maxZ.toFloat()
        val edges = arrayOf(
            floatArrayOf(x1,y1,z1,x2,y1,z1), floatArrayOf(x2,y1,z1,x2,y1,z2),
            floatArrayOf(x2,y1,z2,x1,y1,z2), floatArrayOf(x1,y1,z2,x1,y1,z1),
            floatArrayOf(x1,y2,z1,x2,y2,z1), floatArrayOf(x2,y2,z1,x2,y2,z2),
            floatArrayOf(x2,y2,z2,x1,y2,z2), floatArrayOf(x1,y2,z2,x1,y2,z1),
            floatArrayOf(x1,y1,z1,x1,y2,z1), floatArrayOf(x2,y1,z1,x2,y2,z1),
            floatArrayOf(x2,y1,z2,x2,y2,z2), floatArrayOf(x1,y1,z2,x1,y2,z2)
        )
        for (e in edges) {
            val dx = e[3]-e[0]; val dy = e[4]-e[1]; val dz = e[5]-e[2]
            buf.addVertex(entry, e[0], e[1], e[2]).setColor(r, g, b, a).setNormal(entry, dx, dy, dz).setLineWidth(2.0f)
            buf.addVertex(entry, e[3], e[4], e[5]).setColor(r, g, b, a).setNormal(entry, dx, dy, dz).setLineWidth(2.0f)
        }
    }

    // ── Debug ──────────────────────────────────────────────────────────

    fun debugDump(): String {
        val sb = StringBuilder()
        val cfg = FamilyConfigManager.config.kuudra
        val player = Minecraft.getInstance().player

        sb.append("§6[FA Pearl] §7Flags: ")
            .append("§eenabled=").append(if (cfg.pearlWaypointsEnabled) "§atrue" else "§cfalse")
            .append("§7 ")
            .append("§einKuudra=").append(if (AutoRequeue.isInKuudra()) "§atrue" else "§cfalse")
            .append("§7 ")
            .append("§einP1=").append(if (KuudraPhase.isInP1()) "§atrue" else "§cfalse")
            .append("\n")

        sb.append("§7Kuudra tier: §e").append(AutoRequeue.kuudraTierIndex()).append(" §7|")
            .append(" Talisman: §e").append(cfg.pearlTalismanTier).append(" §7|")
            .append(" maxTime: §e").append(getMaxTimeMs()).append("ms\n")

        sb.append("§7Grabbing: ")
        if (grabbing) {
            sb.append("§atrue §7startTick=§e$grabStartTick §7now=§e$tickCount §7elapsed=§e${(tickCount - grabStartTick) * 50}ms")
            if (nowSoundPlayed) sb.append(" §a[NOW fired]")
        } else {
            sb.append("§cfalse")
        }
        sb.append("\n")

        sb.append("§7Occupied: §e")
            .append(if (KuudraOccupancy.occupiedPlaces.isEmpty()) "(none)" else KuudraOccupancy.occupiedPlaces.joinToString(", "))
            .append("\n")

        sb.append("§7Missing: §e")
            .append(if (MissingSupplies.missing.isEmpty()) "(none)" else MissingSupplies.missing.joinToString(", "))
            .append("\n")

        if (player == null) {
            sb.append("§c No player.\n"); return sb.toString()
        }
        val eye = player.getEyePosition(1f)
        val pre = Pre.getClosestSpot(eye)
        sb.append("§7Player @ §f${"%.1f".format(eye.x)}, ${"%.1f".format(eye.y)}, ${"%.1f".format(eye.z)}\n")
        sb.append("§7Closest Pre: §e$pre\n")

        val supply = Prio.getSupplyForSpot(pre)
        val mainPlace = preToPlace(pre)
        sb.append("§7Supply target: §e${supply ?: "(none)"} §7(place=§e${mainPlace ?: "?"}§7)\n")
        if (supply != null) {
            val sol = PearlCalculator.solvePearl(false, eye, eye, supply)
            if (sol != null) {
                sb.append("§a SOLVED: aim=${"%.1f".format(sol.aimPoint.x)},${"%.1f".format(sol.aimPoint.y)},${"%.1f".format(sol.aimPoint.z)}")
                    .append(" §7yaw=${"%.1f".format(sol.lookYawDeg)}°")
                    .append(" pitch=${"%.1f".format(sol.lookPitchDeg)}°")
                    .append(" t=${sol.flightTimeMs}ms")
                val tStr = timerString(sol.flightTimeMs, false)
                if (tStr != null) sb.append(" timer=").append(tStr)
                sb.append("\n")
            } else {
                sb.append("§c No solution found.\n")
            }
        }

        // Mainhidden + SQUARE-no-target fallback info
        val mainHidden = mainPlace != null && mainPlace in KuudraOccupancy.occupiedPlaces
        val squareNoTarget = (pre == Pre.SQUARE && supply == null)
        if (mainHidden || squareNoTarget) {
            val reason = when {
                mainHidden && squareNoTarget -> "main occupied + SQUARE no target"
                mainHidden                   -> "main pile occupied"
                else                         -> "SQUARE — no missing supplies called"
            }
            val avail = availableFallbackPlaces().filter { it != mainPlace }
            sb.append("§7Fallback active (§e").append(reason).append("§7): §e")
                .append(if (avail.isEmpty()) "(none)" else avail.joinToString(", "))
                .append("\n")
        }

        // Double pearls debug — show every route, mark which are visible/hidden and why.
        sb.append("§7Double Pearls: cfg=§e").append(cfg.pearlDPearls).append("\n")
        for (dp in DoublePearls.dPearls.values) {
            val active = dp.pre == pre
            val destPlace = preToPlace(dp.drop)
            val occluded = destPlace != null && destPlace in KuudraOccupancy.occupiedPlaces
            val missing = cfg.pearlHideOnMissing && destPlace != null && destPlace in MissingSupplies.missing
            val mark = when {
                !active   -> "§8[wrong-pre]"
                occluded  -> "§c[occupied]"
                missing   -> "§c[missing]"
                else      -> "§a[shown]"
            }
            sb.append("  ").append(mark).append(" §f${dp.id} §7@ ${"%.1f".format(dp.location.x)},${"%.1f".format(dp.location.y)},${"%.1f".format(dp.location.z)}\n")
        }

        return sb.toString()
    }
}