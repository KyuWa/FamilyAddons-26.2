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
import org.kyowa.familyaddons.FamilyAddons
import org.kyowa.familyaddons.config.FamilyConfigManager
import org.kyowa.familyaddons.features.pearl.DoublePearls
import org.kyowa.familyaddons.features.pearl.MissingSupplies
import org.kyowa.familyaddons.features.pearl.PearlCalculator
import org.kyowa.familyaddons.features.pearl.Place
import org.kyowa.familyaddons.features.pearl.Pre
import org.kyowa.familyaddons.features.pearl.Prio
import org.lwjgl.opengl.GL11
import kotlin.math.abs
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

    // ── Timing ground truth (debug) ────────────────────────────────────
    // Server ticks between the 0% and 100% grab titles, against the table
    // value we count down from. If these disagree the NOW moment is off by
    // exactly that difference; /fa kuudra prints both.
    @Volatile private var lastGrabTicks: Int = -1
    @Volatile private var lastGrabExpectedTicks: Int = -1
    @Volatile private var lastGrabTps: Double = -1.0

    // ── Grab length model ──────────────────────────────────────────────
    // The pawsup table is only a first guess: measured on 2026-09-07 a
    // Burning/T3-tali grab took 86-87 of our ticks against a table value of
    // 70, which fired NOW ~0.8 s early. So the total is now, in priority:
    //   1. live projection from the progress bar (elapsed * 100 / pct) once
    //      the bar is past LIVE_MIN_PCT — this also absorbs server lag;
    //   2. the duration measured on the previous grab at this tier/talisman;
    //   3. the table.
    private const val LIVE_MIN_PCT = 15
    @Volatile private var estGrabTicks: Int = -1

    // Wall-clock alongside ticks, to tell whether Hypixel's pickup runs on
    // ticks or on real time (they differ during lag catch-up bursts).
    @Volatile private var grabStartMs: Long = 0L
    @Volatile private var lastFinishTick: Int = -1
    @Volatile private var lastFinishMs: Long = 0L
    @Volatile private var lastFinishExpectedTick: Int = -1

    private fun learnedKey() = "${AutoRequeue.kuudraTierIndex()}/${FamilyConfigManager.config.kuudra.pearlTalismanTier}"

    /** Total grab length in our tick units, best current estimate. */
    private fun grabTotalTicks(): Int {
        if (estGrabTicks > 0) return estGrabTicks
        FamilyConfigManager.config.kuudra.pearlLearnedGrabTicks[learnedKey()]?.let { if (it > 0) return it }
        return (getMaxTimeMs() / 50L).toInt()
    }

    // Measured 2026-09-07: the pickup is a fixed 105 ticks at Infernal while
    // its wall time varied 4.26-4.59 s, i.e. the ping stream IS the server's
    // tick stream (~23-24/s on Hypixel) and the pearl is simulated on that
    // same clock, so the solver's flight ticks need no scaling.

    /** Ticks left until the pearl must be thrown; <= 0 means NOW. */
    private fun remainingTicks(flightTimeMs: Long, isDoublePearl: Boolean): Int {
        val cfg = FamilyConfigManager.config.kuudra
        val elapsed = (tickCount - grabStartTick).coerceAtLeast(0)
        val flight = Math.round(flightTimeMs / 50.0).toInt()
        val delay = (cfg.pearlTimerDelay.toLong() / 50L).toInt()
        val reaction = (cfg.pearlReactionMs.toLong() / 50L).toInt()
        val dDelay = if (isDoublePearl) (cfg.pearlDPearlLandDelay.toLong() / 50L).toInt() else 0
        return grabTotalTicks() + dDelay - elapsed - flight + delay - reaction
    }

    fun onTitle(rawTitle: String) {
        val plain = rawTitle.replace(COLOR_CODE_REGEX, "")
        val match = PROGRESS_REGEX.find(plain) ?: return
        val pct = match.groupValues[1].toIntOrNull() ?: return
        when {
            pct == 0 && !grabbing -> {
                grabbing = true
                grabStartTick = tickCount
                grabStartMs = System.currentTimeMillis()
                nowSoundPlayed = false
                estGrabTicks = -1
                devLog("PearlWaypoints: grab started (title '$plain'), table ${getMaxTimeMs() / 50L} ticks, using ${grabTotalTicks()}")
                devChat("§7Grab started, expecting §e${grabTotalTicks()}§7 ticks §8(table ${getMaxTimeMs() / 50L})")
            }
            grabbing && pct in 1..99 -> {
                val elapsed = tickCount - grabStartTick
                if (pct >= LIVE_MIN_PCT && elapsed > 0) {
                    estGrabTicks = Math.round(elapsed * 100.0 / pct).toInt()
                }
            }
            grabbing && pct >= 100 -> finishGrab("100% title")
        }
    }

    /** Grab ended successfully: record how many server ticks it really took. */
    private fun finishGrab(source: String) {
        if (!grabbing) return
        lastGrabTicks = (tickCount - grabStartTick).coerceAtLeast(0)
        lastGrabExpectedTicks = (getMaxTimeMs() / 50L).toInt()
        lastGrabTps = ServerTickTracker.observedTps()
        val grabMs = System.currentTimeMillis() - grabStartMs
        lastFinishTick = tickCount
        lastFinishMs = System.currentTimeMillis()
        lastFinishExpectedTick = grabStartTick + grabTotalTicks()
        devLog("PearlWaypoints: grab wall time ${grabMs} ms for $lastGrabTicks ticks (${"%.1f".format(lastGrabTicks * 1000.0 / grabMs.coerceAtLeast(1))} ticks/s over the grab), expected finish at tick $lastFinishExpectedTick, actual $lastFinishTick")
        if (lastGrabTicks > 10) {
            val cfg = FamilyConfigManager.config.kuudra
            val key = learnedKey()
            val prev = cfg.pearlLearnedGrabTicks[key]
            // Average with the previous measurement so one laggy grab does not
            // swing the next run's opening estimate too far.
            cfg.pearlLearnedGrabTicks[key] = if (prev == null || prev <= 0) lastGrabTicks else (prev + lastGrabTicks + 1) / 2
            FamilyConfigManager.save()
        }
        devLog("PearlWaypoints: grab took $lastGrabTicks server ticks ($source), table says $lastGrabExpectedTicks (observed ${"%.1f".format(lastGrabTps)} ticks/s)")
        devChat("§7Grab took §e$lastGrabTicks§7 server ticks §8($source)§7, table §e$lastGrabExpectedTicks§7, tick rate §e${"%.1f".format(lastGrabTps)}§7/s")
        clearGrab()
    }

    /** Diagnostics go to the log file only on the dev account. */
    private fun devLog(msg: String) {
        if (org.kyowa.familyaddons.util.DevAccess.debug()) FamilyAddons.LOGGER.info(msg)
    }

    private fun devChat(msg: String) {
        if (!org.kyowa.familyaddons.util.DevAccess.debug()) return
        val mc = Minecraft.getInstance()
        mc.execute { mc.player?.sendSystemMessage(org.kyowa.familyaddons.util.FaChat.prefixed(msg)) }
    }

    private fun clearGrab() {
        grabbing = false
        grabStartTick = -1
        nowSoundPlayed = false
    }

    /**
     * Called from PlayerPositionPacketMixin on every server teleport. During
     * or just after a grab that is the pearl landing: log where it landed
     * relative to the pickup finishing (the number that decides whether a
     * supply is kept). Negative = landed before the pickup was done.
     */
    fun onTeleport(pos: Vec3) {
        val now = System.currentTimeMillis()
        if (grabbing) {
            val elapsed = tickCount - grabStartTick
            devLog("PearlWaypoints: teleport DURING grab at tick $elapsed / ${grabTotalTicks()} expected (${now - grabStartMs} ms in) — landed ${grabTotalTicks() - elapsed} ticks early")
            devChat("§cPearl landed §e${grabTotalTicks() - elapsed}§c ticks before the pickup finished")
        } else if (lastFinishTick >= 0 && now - lastFinishMs < 3000) {
            val late = tickCount - lastFinishTick
            devLog("PearlWaypoints: teleport $late ticks / ${now - lastFinishMs} ms after the pickup finished")
            devChat("§7Pearl landed §a$late§7 ticks after the pickup finished")
        }
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

        // The grab progress bar advances on SERVER ticks, so the throw-window
        // countdown has to count the same clock. Hypixel's per-tick ping packet
        // (ServerTickTracker) stops arriving while the server lags, which
        // pauses the countdown exactly like the grab bar pauses — a client
        // tick counter kept running at 20 Hz and called "NOW" too early.
        ServerTickTracker.onTick { tickCount++ }

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            Prio.useNewPrio = FamilyConfigManager.config.kuudra.pearlNewPrio

            if (!KuudraPhase.isInP1()) {
                if (grabbing) clearGrab()
                if (MissingSupplies.missing.isNotEmpty()) MissingSupplies.clear()
            }

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
            // Success line: this is the real end of the pickup (it usually
            // lands before a 100% title is ever shown), so measure here.
            plain == "You retrieved some of Elle's supplies from the Lava!" -> finishGrab("chat")
            plain in GRAB_LOSS_LINES -> {
                if (grabbing) devLog("PearlWaypoints: grab cancelled after ${(tickCount - grabStartTick).coerceAtLeast(0)} server ticks / ${System.currentTimeMillis() - grabStartMs} ms ('$plain'), model expected ${grabTotalTicks()} ticks")
                clearGrab()
            }
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

        return remainingTicks(sol.flightTimeMs, isDoublePearl = false) <= 0
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
        val remaining = remainingTicks(flightTimeMs, isDoublePearl)
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

    /**
     * Pearl aim-point marker. Shapes (index = config dropdown):
     *  0 ESP Box      - translucent filled cube + outline
     *  1 Box Outline  - wireframe cube only
     *  2 Flat Square  - translucent horizontal square + outline at the aim height
     *  3 Flat Circle  - translucent horizontal disc + outline at the aim height
     * Every shape is drawn depth-tested, then again faintly through walls.
     */
    internal fun drawWaypoint(
        matrices: PoseStack,
        collector: SubmitNodeCollector,
        pos: Vec3,
        color: FloatArray,
        size: Double,
        shape: Int,
    ) {
        val half = size.coerceAtLeast(0.05) / 2.0
        when (shape) {
            0 -> { drawFilledBox(matrices, collector, pos, half, color); drawBoxOutline(matrices, collector, pos, half, color) }
            1 -> drawBoxOutline(matrices, collector, pos, half, color)
            2 -> drawFlatSquare(matrices, collector, pos, half, color)
            3 -> drawFlatCircle(matrices, collector, pos, half, color)
            4 -> drawTarget(matrices, collector, pos, color)
            else -> drawBoxOutline(matrices, collector, pos, half, color)
        }
    }

    /** Alpha of the filled faces relative to the configured colour alpha. */
    private const val FILL_ALPHA = 0.35f

    private fun drawBoxOutline(
        matrices: PoseStack, collector: SubmitNodeCollector,
        pos: Vec3, half: Double, color: FloatArray,
    ) {
        val r = color[0]; val g = color[1]; val b = color[2]; val a = color[3]
        val box = AABB(pos.x - half, pos.y - half, pos.z - half, pos.x + half, pos.y + half, pos.z + half)
        collector.submitCustomGeometry(matrices, FamilyRenderTypes.LINES) { pose, buf ->
            boxEdges(buf, pose, box, r, g, b, a)
        }
        collector.submitCustomGeometry(matrices, FamilyRenderTypes.LINES_NO_DEPTH) { pose, buf ->
            boxEdges(buf, pose, box, r, g, b, a * 0.3f)
        }
    }

    private fun drawFilledBox(
        matrices: PoseStack, collector: SubmitNodeCollector,
        pos: Vec3, half: Double, color: FloatArray,
    ) {
        val r = color[0]; val g = color[1]; val b = color[2]; val a = color[3] * FILL_ALPHA
        val x1 = (pos.x - half).toFloat(); val y1 = (pos.y - half).toFloat(); val z1 = (pos.z - half).toFloat()
        val x2 = (pos.x + half).toFloat(); val y2 = (pos.y + half).toFloat(); val z2 = (pos.z + half).toFloat()
        collector.submitCustomGeometry(matrices, FamilyRenderTypes.BEAM) { pose, buf ->
            fun q(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, cx: Float, cy: Float, cz: Float, dx: Float, dy: Float, dz: Float) {
                buf.addVertex(pose, ax, ay, az).setColor(r, g, b, a)
                buf.addVertex(pose, bx, by, bz).setColor(r, g, b, a)
                buf.addVertex(pose, cx, cy, cz).setColor(r, g, b, a)
                buf.addVertex(pose, dx, dy, dz).setColor(r, g, b, a)
            }
            // Both windings so the box is visible from inside and outside (the quad pipeline culls).
            q(x1,y1,z1, x2,y1,z1, x2,y2,z1, x1,y2,z1); q(x1,y2,z1, x2,y2,z1, x2,y1,z1, x1,y1,z1) // -z
            q(x1,y1,z2, x1,y2,z2, x2,y2,z2, x2,y1,z2); q(x2,y1,z2, x2,y2,z2, x1,y2,z2, x1,y1,z2) // +z
            q(x1,y1,z1, x1,y2,z1, x1,y2,z2, x1,y1,z2); q(x1,y1,z2, x1,y2,z2, x1,y2,z1, x1,y1,z1) // -x
            q(x2,y1,z1, x2,y1,z2, x2,y2,z2, x2,y2,z1); q(x2,y2,z1, x2,y2,z2, x2,y1,z2, x2,y1,z1) // +x
            q(x1,y1,z1, x1,y1,z2, x2,y1,z2, x2,y1,z1); q(x2,y1,z1, x2,y1,z2, x1,y1,z2, x1,y1,z1) // -y
            q(x1,y2,z1, x2,y2,z1, x2,y2,z2, x1,y2,z2); q(x1,y2,z2, x2,y2,z2, x2,y2,z1, x1,y2,z1) // +y
        }
    }

    /** Horizontal ring of [pts] (closed) at height [cy]: filled fan + outline. */
    private fun drawFlatPolygon(
        matrices: PoseStack, collector: SubmitNodeCollector,
        cx: Float, cy: Float, cz: Float, pts: List<Pair<Float, Float>>, color: FloatArray,
    ) {
        val r = color[0]; val g = color[1]; val b = color[2]; val a = color[3]

        // Fill: one quad per edge (centre, p[i], p[i+1], centre), both windings.
        val fa = a * FILL_ALPHA
        collector.submitCustomGeometry(matrices, FamilyRenderTypes.BEAM) { pose, fill ->
            for (i in 0 until pts.size - 1) {
                val (x0, z0) = pts[i]; val (x1, z1) = pts[i + 1]
                fill.addVertex(pose, cx, cy, cz).setColor(r, g, b, fa)
                fill.addVertex(pose, x0, cy, z0).setColor(r, g, b, fa)
                fill.addVertex(pose, x1, cy, z1).setColor(r, g, b, fa)
                fill.addVertex(pose, cx, cy, cz).setColor(r, g, b, fa)
                fill.addVertex(pose, cx, cy, cz).setColor(r, g, b, fa)
                fill.addVertex(pose, x1, cy, z1).setColor(r, g, b, fa)
                fill.addVertex(pose, x0, cy, z0).setColor(r, g, b, fa)
                fill.addVertex(pose, cx, cy, cz).setColor(r, g, b, fa)
            }
        }

        // Outline, depth-tested then faint through walls.
        fun edges(renderType: RenderType, alpha: Float) {
            collector.submitCustomGeometry(matrices, renderType) { pose, buf ->
                for (i in 0 until pts.size - 1) {
                    val (x0, z0) = pts[i]; val (x1, z1) = pts[i + 1]
                    val dx = x1 - x0; val dz = z1 - z0
                    val len = sqrt((dx * dx + dz * dz).toDouble()).toFloat().coerceAtLeast(1e-4f)
                    buf.addVertex(pose, x0, cy, z0).setColor(r, g, b, alpha).setNormal(pose, dx / len, 0f, dz / len).setLineWidth(2.0f)
                    buf.addVertex(pose, x1, cy, z1).setColor(r, g, b, alpha).setNormal(pose, dx / len, 0f, dz / len).setLineWidth(2.0f)
                }
            }
        }
        edges(FamilyRenderTypes.LINES, a)
        edges(FamilyRenderTypes.LINES_NO_DEPTH, a * 0.3f)
    }

    /**
     * "Target": a ring facing the camera around the aim point (the tolerance:
     * throws inside it usually land, not always) with a solid dot at the exact
     * aim point (the perfect throw). Both sizes come from the config.
     */
    private fun drawTarget(
        matrices: PoseStack, collector: SubmitNodeCollector,
        pos: Vec3, color: FloatArray,
    ) {
        val cfg = FamilyConfigManager.config.kuudra
        val ringR = cfg.pearlTargetRingRadius.toDouble().coerceIn(0.05, 5.0)
        val dotR = cfg.pearlTargetDotRadius.toDouble().coerceIn(0.01, 2.0)
        val r = color[0]; val g = color[1]; val b = color[2]; val a = color[3]

        // Basis of the plane facing the camera.
        val cam = Minecraft.getInstance().gameRenderer.mainCamera().position()
        var n = pos.subtract(cam)
        if (n.lengthSqr() < 1e-6) n = Vec3(0.0, 0.0, 1.0)
        n = n.normalize()
        val up = if (abs(n.y) > 0.99) Vec3(1.0, 0.0, 0.0) else Vec3(0.0, 1.0, 0.0)
        val u = n.cross(up).normalize()
        val v = n.cross(u).normalize()

        fun ring(radius: Double, segments: Int): List<Vec3> = (0..segments).map { i ->
            val ang = Math.PI * 2.0 * i / segments
            pos.add(u.scale(Math.cos(ang) * radius)).add(v.scale(Math.sin(ang) * radius))
        }

        // Outer ring: outline, depth-tested then faint through walls.
        val outer = ring(ringR, 48)
        fun ringLines(renderType: RenderType, alpha: Float) {
            collector.submitCustomGeometry(matrices, renderType) { pose, buf ->
                for (i in 0 until outer.size - 1) {
                    val p0 = outer[i]; val p1 = outer[i + 1]
                    val d = p1.subtract(p0).normalize()
                    buf.addVertex(pose, p0.x.toFloat(), p0.y.toFloat(), p0.z.toFloat()).setColor(r, g, b, alpha)
                        .setNormal(pose, d.x.toFloat(), d.y.toFloat(), d.z.toFloat()).setLineWidth(2.5f)
                    buf.addVertex(pose, p1.x.toFloat(), p1.y.toFloat(), p1.z.toFloat()).setColor(r, g, b, alpha)
                        .setNormal(pose, d.x.toFloat(), d.y.toFloat(), d.z.toFloat()).setLineWidth(2.5f)
                }
            }
        }
        ringLines(FamilyRenderTypes.LINES, a)
        ringLines(FamilyRenderTypes.LINES_NO_DEPTH, a * 0.3f)

        // Centre dot: solid filled disc (fan of quads, both windings).
        val dot = ring(dotR, 20)
        val cx = pos.x.toFloat(); val cy = pos.y.toFloat(); val cz = pos.z.toFloat()
        collector.submitCustomGeometry(matrices, FamilyRenderTypes.BEAM) { pose, fill ->
            for (i in 0 until dot.size - 1) {
                val p0 = dot[i]; val p1 = dot[i + 1]
                fill.addVertex(pose, cx, cy, cz).setColor(r, g, b, a)
                fill.addVertex(pose, p0.x.toFloat(), p0.y.toFloat(), p0.z.toFloat()).setColor(r, g, b, a)
                fill.addVertex(pose, p1.x.toFloat(), p1.y.toFloat(), p1.z.toFloat()).setColor(r, g, b, a)
                fill.addVertex(pose, cx, cy, cz).setColor(r, g, b, a)
                fill.addVertex(pose, cx, cy, cz).setColor(r, g, b, a)
                fill.addVertex(pose, p1.x.toFloat(), p1.y.toFloat(), p1.z.toFloat()).setColor(r, g, b, a)
                fill.addVertex(pose, p0.x.toFloat(), p0.y.toFloat(), p0.z.toFloat()).setColor(r, g, b, a)
                fill.addVertex(pose, cx, cy, cz).setColor(r, g, b, a)
            }
        }
    }

    private fun drawFlatSquare(
        matrices: PoseStack, collector: SubmitNodeCollector,
        center: Vec3, half: Double, color: FloatArray,
    ) {
        val cx = center.x.toFloat(); val cy = center.y.toFloat(); val cz = center.z.toFloat()
        val h = half.toFloat()
        val pts = listOf(cx - h to cz - h, cx + h to cz - h, cx + h to cz + h, cx - h to cz + h, cx - h to cz - h)
        drawFlatPolygon(matrices, collector, cx, cy, cz, pts, color)
    }

    private fun drawFlatCircle(
        matrices: PoseStack, collector: SubmitNodeCollector,
        center: Vec3, radius: Double, color: FloatArray,
    ) {
        val cx = center.x.toFloat(); val cy = center.y.toFloat(); val cz = center.z.toFloat()
        val segments = 32
        val pts = (0..segments).map { i ->
            val angle = Math.PI * 2.0 * i / segments
            (cx + radius * Math.cos(angle)).toFloat() to (cz + radius * Math.sin(angle)).toFloat()
        }
        drawFlatPolygon(matrices, collector, cx, cy, cz, pts, color)
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
        sb.append("§7Grab model: §e${grabTotalTicks()}§7 ticks (table ${getMaxTimeMs() / 50L}, learned ${cfg.pearlLearnedGrabTicks[learnedKey()] ?: "-"}, live ${if (estGrabTicks > 0) estGrabTicks else "-"})\n")
        sb.append("§7Server tick rate now: §e${"%.1f".format(ServerTickTracker.observedTps())}§7/s")
        if (lastGrabTicks >= 0) {
            sb.append(" §7| last grab: §e$lastGrabTicks§7 ticks vs table §e$lastGrabExpectedTicks§7 (rate then §e${"%.1f".format(lastGrabTps)}§7/s)")
        }
        sb.append("\n")

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