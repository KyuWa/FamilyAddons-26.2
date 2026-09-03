package org.kyowa.familyaddons.features

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.resources.Identifier
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl
import net.minecraft.world.item.EnderpearlItem
import net.minecraft.world.item.ItemStack
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionResult
import net.minecraft.world.InteractionHand
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.level.ClipContext
import org.kyowa.familyaddons.COLOR_CODE_REGEX
import org.kyowa.familyaddons.FamilyAddons
import org.kyowa.familyaddons.config.FamilyConfigManager
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pearl land-time timer for Hypixel SkyBlock.
 *
 * Hybrid approach (prediction + entity tracking):
 *
 *  1. On right-click, simulate the pearl's flight using the same physics
 *     constants the vanilla server uses (matches Odin's Trajectories module):
 *
 *       - initial speed 1.5 b/t
 *       - per tick: motion = (motion.x * 0.99, (motion.y - 0.03) * 0.99, motion.z * 0.99)
 *
 *     This gives an immediate visible estimate so the user sees feedback the
 *     instant they click.
 *
 *  2. Within ~10 ticks, find the spawned ThrownEnderpearl owned by the local
 *     player and bind it to our timer entry. Once bound, we no longer rely on
 *     prediction for the END condition — we end the timer the moment the
 *     entity dies (i.e. the server marked it as collided, regardless of
 *     whether it hit a block, a mob, or another player).
 *
 *  3. If we never bind an entity (chunk loading lag, very rare), fall back to
 *     pure prediction — same behavior as before.
 *
 * This fixes both edge cases the old pure-prediction approach got wrong:
 *
 *  - Mob walks under the pearl mid-flight   → entity dies early → timer ends
 *  - Pearl flies further than predicted     → entity still alive → we wait
 *
 * Multiple pearls in flight are stacked in a list and displayed
 * "Pearl 1: 1.20s / Pearl 2: 0.85s" (or in ticks) in throw order.
 */
object PearlTimer {

    // Hard cap on how far we'll simulate. ~120 ticks = 6s, which is comfortably
    // longer than any reasonable pearl arc on Hypixel before it lands or despawns.
    private const val SIM_RANGE_TICKS = 120

    // Minimum ticks between accepted throws. Right-clicking 5 times the same
    // tick shouldn't queue 5 timers — only the first throw actually leaves
    // the player.
    private const val THROW_DEBOUNCE_TICKS = 2

    // How long to wait for the ThrownEnderpearl to appear in the world before
    // giving up and falling back to pure prediction. The pearl typically
    // spawns within 1-2 ticks of the throw; 10 is a generous safety margin.
    private const val BIND_TIMEOUT_TICKS = 10

    // After the entity dies (or is unbindable), keep showing "0.00s" briefly
    // so the user gets a clear visual confirmation of the landing instead of
    // the line just vanishing. 4 ticks = 200 ms.
    private const val POST_LAND_HOLD_TICKS = 4

    private enum class State { PENDING_BIND, TRACKING, PURE_PREDICTION, LANDED }

    private data class PearlEntry(
        val id: Int,                    // Display label (Pearl 1, 2, ...)
        var remainingTicks: Int,        // Ticks left in the prediction
        var ticksSinceThrow: Int = 0,   // Used for the bind timeout
        var state: State = State.PENDING_BIND,
        var boundEntityId: Int = -1,    // World entity id of the bound pearl
        var postLandHold: Int = 0       // Ticks to keep showing "0" after landing
    )

    @Volatile private var nextLabel = 1
    private val pearls = ArrayList<PearlEntry>()
    private var ticksSinceLastThrow = THROW_DEBOUNCE_TICKS

    // Preview text variants used by the HUD editor.
    const val PREVIEW_TEXT_SECONDS_1 = "§dPearl 1 §f1.20s"
    const val PREVIEW_TEXT_SECONDS_2 = "§dPearl 2 §f0.85s"
    const val PREVIEW_TEXT_TICKS_1 = "§dPearl 1 §f24t"
    const val PREVIEW_TEXT_TICKS_2 = "§dPearl 2 §f17t"

    fun previewLine1(): String =
        if (FamilyConfigManager.config.kuudra.pearlDisplayUnit == 1) PREVIEW_TEXT_TICKS_1
        else PREVIEW_TEXT_SECONDS_1

    fun previewLine2(): String =
        if (FamilyConfigManager.config.kuudra.pearlDisplayUnit == 1) PREVIEW_TEXT_TICKS_2
        else PREVIEW_TEXT_SECONDS_2

    fun getScale(): Float = FamilyConfigManager.config.kuudra.pearlTimerHudScale
        .toFloatOrNull()?.coerceAtLeast(0.5f) ?: 1.0f

    fun resolvePos(sw: Int, sh: Int, scale: Float, textWidth: Int): Pair<Int, Int> {
        val cfg = FamilyConfigManager.config.kuudra
        return if (cfg.pearlTimerHudX == -1 || cfg.pearlTimerHudY == -1) {
            val x = ((sw - textWidth * scale) / 2f).toInt()
            val y = (sh / 2f + 60f).toInt()
            x to y
        } else {
            cfg.pearlTimerHudX to cfg.pearlTimerHudY
        }
    }

    /** Spirit pearls are wings, not throwable pearls — exclude them. */
    private fun isThrowablePearl(stack: ItemStack): Boolean {
        if (stack.isEmpty) return false
        if (stack.item !is EnderpearlItem) return false
        val name = stack.hoverName.string ?: return true
        if (name.contains("Spirit", ignoreCase = true)) return false
        return true
    }

    private fun predictLandingTicks(): Int? {
        val client = Minecraft.getInstance()
        val player = client.player ?: return null
        val world = client.level ?: return null

        val yawRad = Math.toRadians(player.yRot.toDouble())
        val ox = -cos(yawRad) * 0.16
        val oz = -sin(yawRad) * 0.16
        var pos = Vec3(player.x + ox, player.eyeY - 0.1, player.z + oz)

        var motion = lookVector(player.yRot, player.xRot).normalize().scale(1.5)

        for (tick in 1..SIM_RANGE_TICKS) {
            val nextPos = pos.add(motion)

            val hit = world.clip(
                ClipContext(
                    pos,
                    nextPos,
                    ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE,
                    player
                )
            )
            if (hit.type == HitResult.Type.BLOCK && hit is BlockHitResult) {
                val segLen = motion.length()
                val toImpact = pos.distanceTo(hit.location)
                val frac = if (segLen > 1e-6) (toImpact / segLen) else 1.0
                return ((tick - 1) + frac).coerceAtLeast(0.0)
                    .let { ceil(it).toInt().coerceAtLeast(1) }
            }

            pos = nextPos
            motion = Vec3(motion.x * 0.99, (motion.y - 0.03) * 0.99, motion.z * 0.99)
        }

        return null
    }

    private fun lookVector(yaw: Float, pitch: Float): Vec3 {
        val f = -cos(-pitch * 0.017453292)
        return Vec3(
            sin(-yaw * 0.017453292 - Math.PI) * f,
            sin(-pitch * 0.017453292).toDouble(),
            cos(-yaw * 0.017453292 - Math.PI) * f
        )
    }

    /**
     * Look for unbound EnderPearlEntities owned by the local player and bind
     * them to our PENDING_BIND entries in throw order. We bind the oldest
     * pending entry to the youngest unbound pearl entity (by entity age) so
     * that rapid back-to-back throws bind correctly.
     */
    private fun tryBindPearls() {
        val client = Minecraft.getInstance()
        val player = client.player ?: return
        val world = client.level ?: return

        // Collect entries that still need binding.
        val pending = pearls.filter { it.state == State.PENDING_BIND }
        if (pending.isEmpty()) return

        // Collect player-owned pearl entities not already bound to any entry.
        val alreadyBoundIds = pearls.mapNotNullTo(HashSet()) {
            if (it.boundEntityId != -1) it.boundEntityId else null
        }
        val candidates = ArrayList<ThrownEnderpearl>()
        for (entity in world.entitiesForRendering()) {
            if (entity !is ThrownEnderpearl) continue
            if (entity.id in alreadyBoundIds) continue
            // Owner check — only bind pearls thrown by us. On Hypixel, owner
            // metadata is sent for pearls so this is reliable.
            if (entity.owner !== player) continue
            candidates.add(entity)
        }
        if (candidates.isEmpty()) return

        // Sort candidates by age descending (newest = lowest age first), so the
        // most recently spawned pearl binds to the most recently thrown entry.
        candidates.sortBy { it.tickCount }

        // Bind in order. There may be more pending than candidates or vice
        // versa — that's fine, each is matched up to the available count.
        val toBind = minOf(pending.size, candidates.size)
        // Reverse-iterate pending so the OLDEST pending throw binds to the
        // OLDEST candidate (highest age). This keeps Pearl 1 → first-thrown.
        for (i in 0 until toBind) {
            val entry = pending[i]               // oldest-first
            val pearl = candidates[candidates.size - 1 - i] // oldest-first
            entry.boundEntityId = pearl.id
            entry.state = State.TRACKING
        }
    }

    fun register() {
        ServerTickTracker.onTick {
            if (ticksSinceLastThrow < THROW_DEBOUNCE_TICKS) ticksSinceLastThrow++

            if (pearls.isEmpty()) {
                if (nextLabel != 1) nextLabel = 1
                return@onTick
            }

            // Try to bind any pending entries to spawned pearl entities.
            tryBindPearls()

            val world = Minecraft.getInstance().level

            val it = pearls.iterator()
            while (it.hasNext()) {
                val entry = it.next()
                entry.ticksSinceThrow++

                when (entry.state) {
                    State.PENDING_BIND -> {
                        // Still waiting for the entity. Decrement so the
                        // visible timer keeps moving — if binding succeeds
                        // we'll switch to entity-driven termination from
                        // there. If we time out, fall back to pure prediction.
                        entry.remainingTicks--
                        if (entry.ticksSinceThrow >= BIND_TIMEOUT_TICKS) {
                            entry.state = State.PURE_PREDICTION
                        }
                        if (entry.remainingTicks <= 0) {
                            entry.remainingTicks = 0
                            entry.state = State.LANDED
                            entry.postLandHold = POST_LAND_HOLD_TICKS
                        }
                    }
                    State.TRACKING -> {
                        // Authoritative end signal: the entity is gone or dead.
                        // The server marks pearls dead the instant they collide
                        // with anything (block, mob, player) — so this is exact.
                        val ent = world?.getEntity(entry.boundEntityId)
                        if (ent == null || !ent.isAlive) {
                            entry.remainingTicks = 0
                            entry.state = State.LANDED
                            entry.postLandHold = POST_LAND_HOLD_TICKS
                        } else {
                            entry.remainingTicks--
                            // Don't auto-end on remainingTicks <= 0 here —
                            // we trust the entity. If the prediction was a
                            // tick or two short, we just clamp to 0 visually
                            // and keep waiting.
                            if (entry.remainingTicks < 0) entry.remainingTicks = 0
                        }
                    }
                    State.PURE_PREDICTION -> {
                        entry.remainingTicks--
                        if (entry.remainingTicks <= 0) {
                            entry.remainingTicks = 0
                            entry.state = State.LANDED
                            entry.postLandHold = POST_LAND_HOLD_TICKS
                        }
                    }
                    State.LANDED -> {
                        entry.postLandHold--
                        if (entry.postLandHold <= 0) it.remove()
                    }
                }
            }

            if (pearls.isEmpty()) nextLabel = 1
        }

        ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
            ServerTickTracker.reset()
            pearls.clear()
            nextLabel = 1
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            ServerTickTracker.reset()
            pearls.clear()
            nextLabel = 1
        }

        UseItemCallback.EVENT.register { player, _, hand ->
            if (!FamilyConfigManager.config.kuudra.pearlTimer) return@register InteractionResult.PASS
            val client = Minecraft.getInstance()
            if (player != client.player) return@register InteractionResult.PASS
            if (hand != InteractionHand.MAIN_HAND) return@register InteractionResult.PASS

            val stack = player.getItemInHand(hand)
            if (!isThrowablePearl(stack)) return@register InteractionResult.PASS

            if (ticksSinceLastThrow < THROW_DEBOUNCE_TICKS) return@register InteractionResult.PASS

            val landTicks = predictLandingTicks()
            if (landTicks == null) {
                FamilyAddons.LOGGER.debug("PearlTimer: no impact predicted")
                return@register InteractionResult.PASS
            }

            pearls.add(PearlEntry(id = nextLabel, remainingTicks = landTicks))
            nextLabel++
            ticksSinceLastThrow = 0

            InteractionResult.PASS
        }

        HudElementRegistry.addLast(
            Identifier.fromNamespaceAndPath("familyaddons", "pearl_timer"),
            HudElement { context, _ -> renderHud(context) }
        )
    }

    private fun renderHud(context: GuiGraphicsExtractor) {
            if (!FamilyConfigManager.config.kuudra.pearlTimer) return
            if (pearls.isEmpty()) return

            val client = Minecraft.getInstance()
            val tr = client.font
            val scale = getScale()
            val fractional = ServerTickTracker.fractionalTicksSinceLastTick()
            val unit = FamilyConfigManager.config.kuudra.pearlDisplayUnit

            // Pre-build all lines so we can size the box & center properly.
            val lines = ArrayList<String>(pearls.size)
            var widestPlain = 0
            for (entry in pearls) {
                // After landing, pin to 0 so the smoothing doesn't render a
                // small negative value during the post-land hold.
                val displayTicks = if (entry.state == State.LANDED) 0.0
                else (entry.remainingTicks - fractional).coerceAtLeast(0.0)
                val line = formatPearlLine(entry.id, displayTicks, unit)
                lines.add(line)
                val w = tr.width(line.replace(COLOR_CODE_REGEX, ""))
                if (w > widestPlain) widestPlain = w
            }

            val (x, y) = resolvePos(context.guiWidth(), context.guiHeight(), scale, widestPlain)
            val matrices = context.pose()
            matrices.pushMatrix()
            matrices.translate(x.toFloat(), y.toFloat())
            matrices.scale(scale, scale)
            var ly = 0
            for (line in lines) {
                context.text(tr, Component.literal(line), 0, ly, -1, true)
                ly += 10
            }
            matrices.popMatrix()
    }

    /**
     * Renders one pearl's countdown.
     *  - Seconds: smooth fractional, e.g. "1.20s"
     *  - Ticks:   integer using ceil so the displayed value drops at the same
     *             instant as the underlying tick.
     */
    private fun formatPearlLine(id: Int, displayTicks: Double, unit: Int): String {
        return when (unit) {
            1 -> {
                val whole = ceil(displayTicks).toInt().coerceAtLeast(0)
                "§dPearl $id §f${whole}t"
            }
            else -> {
                val seconds = displayTicks * 0.05
                "§dPearl $id §f${"%.2f".format(seconds)}s"
            }
        }
    }
}