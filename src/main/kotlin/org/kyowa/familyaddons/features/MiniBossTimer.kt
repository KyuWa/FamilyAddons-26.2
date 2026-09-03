package org.kyowa.familyaddons.features

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import org.kyowa.familyaddons.COLOR_CODE_REGEX
import org.kyowa.familyaddons.config.FamilyConfigManager

/**
 * Mini Boss Timer — Crimson Isle
 *
 * Life-cycle per boss:
 *   -1           → not triggered (hidden)
 *   2400 → 1     → counting down, shows MM:SS
 *   0            → countdown done; spawningHold armed at 20
 *   spawningHold → counts 20 → 0, shows "§aSpawning", then resets to -1 (hidden)
 *
 * Compatible with both 1.21.10 and 1.21.11.
 */
object MiniBossTimer {

    private const val DURATION_TICKS      = 2500 // 2 min × 20 ticks/s
    private const val SPAWNING_HOLD_TICKS = 20   // 1 s at 20 Hz

    enum class Boss(val displayName: String, val triggerPattern: String) {
        MAGMA_BOSS    ("§cMagma Boss",       "MAGMA BOSS DOWN"),
        BARBARIAN_DUKE("§6Barbarian Duke X", "BARBARIAN DUKE X DOWN"),
        BLADESOUL     ("§5BladeSoul",        "BLADESOUL DOWN"),
        MAGE_OUTLAW   ("§9Mage Outlaw",      "MAGE OUTLAW DOWN"),
        ASHFANG       ("§4Ashfang",          "ASHFANG DOWN"),
    }

    /**
     * remaining[boss]:
     *   -1  = not triggered (entry hidden)
     *   > 0 = ticks left in countdown
     *   0   = countdown done, waiting for spawningHold to expire
     */
    private val remaining    = mutableMapOf<Boss, Int>().apply { Boss.values().forEach { put(it, -1) } }
    /** Counts down from SPAWNING_HOLD_TICKS to 0 once remaining hits 0. */
    private val spawningHold = mutableMapOf<Boss, Int>().apply { Boss.values().forEach { put(it, 0) } }

    private fun isEnabled() = FamilyConfigManager.config.crimsonIsle.miniBossTimer

    fun register() {

        // ── Server tick countdown ─────────────────────────────
        ServerTickTracker.onTick {
            if (!isEnabled()) return@onTick
            for (boss in Boss.values()) {
                when (val r = remaining[boss] ?: -1) {
                    -1   -> Unit
                    0    -> {
                        // Tick down the spawning hold
                        val hold = (spawningHold[boss] ?: 0) - 1
                        if (hold <= 0) {
                            remaining[boss] = -1   // hide entry
                            spawningHold[boss] = 0
                        } else {
                            spawningHold[boss] = hold
                        }
                    }
                    else -> {
                        val next = r - 1
                        remaining[boss] = next
                        if (next == 0) spawningHold[boss] = SPAWNING_HOLD_TICKS // arm hold
                    }
                }
            }
        }

        // ── Chat message detection ────────────────────────────
        ClientReceiveMessageEvents.ALLOW_GAME.register { message, _ ->
            if (isEnabled()) {
                val plain = message.string.replace(COLOR_CODE_REGEX, "").trim().uppercase()
                for (boss in Boss.values()) {
                    if (plain.contains(boss.triggerPattern)) {
                        remaining[boss] = DURATION_TICKS
                        spawningHold[boss] = 0
                        break
                    }
                }
            }
            true
        }

        // ── World join / disconnect reset ─────────────────────
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> resetAll() }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> resetAll() }

        // ── HUD rendering ─────────────────────────────────────
        HudElementRegistry.addLast(
            Identifier.fromNamespaceAndPath("familyaddons", "miniboss_timer"),
            HudElement { context, _ -> renderHud(context) }
        )
    }

    private fun renderHud(context: GuiGraphicsExtractor) {
            if (!isEnabled()) return

            val active = Boss.values().filter { (remaining[it] ?: -1) >= 0 }
            if (active.isEmpty()) return

            val client = Minecraft.getInstance()
            val tr = client.font
            val fractional = ServerTickTracker.fractionalTicksSinceLastTick()
            val scale = getScale()

            val lines = active.map { boss ->
                val ticks = remaining[boss] ?: 0
                val timeStr = if (ticks == 0) {
                    "§aSpawning"
                } else {
                    val displayTicks = (ticks - fractional).coerceAtLeast(0.0)
                    val totalSeconds = (displayTicks / 20.0).toInt()
                    val minutes = totalSeconds / 60
                    val seconds = totalSeconds % 60
                    "§f%d:%02d".format(minutes, seconds)
                }
                "${boss.displayName} §7- $timeStr"
            }

            val hudX = FamilyConfigManager.config.crimsonIsle.miniBossHudX.takeIf { it >= 0 } ?: 10
            val hudY = FamilyConfigManager.config.crimsonIsle.miniBossHudY.takeIf { it >= 0 } ?: 10
            val lineHeight = (tr.lineHeight + 2) * scale
            val matrices = context.pose()

            lines.forEachIndexed { i, line ->
                matrices.pushMatrix()
                matrices.translate(hudX.toFloat(), hudY + i * lineHeight)
                matrices.scale(scale, scale)
                context.text(tr, Component.literal(line), 0, 0, -1, true)
                matrices.popMatrix()
            }
    }

    private fun resetAll() {
        Boss.values().forEach { remaining[it] = -1; spawningHold[it] = 0 }
    }

    private fun getScale(): Float =
        FamilyConfigManager.config.crimsonIsle.miniBossHudScale
            .toFloatOrNull()?.coerceAtLeast(0.5f) ?: 1.5f
}
