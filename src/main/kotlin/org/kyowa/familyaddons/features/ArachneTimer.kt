package org.kyowa.familyaddons.features

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import org.kyowa.familyaddons.COLOR_CODE_REGEX
import org.kyowa.familyaddons.config.FamilyConfigManager

object ArachneTimer {

    private const val CRYSTAL_DURATION_MS = 40_000L  // Arachne Crystal → 40s
    private const val CALLING_DURATION_MS = 20_000L  // Arachne's Calling (4/4) → 20s

    private val callingPattern = Regex(
        """[a-zA-Z0-9_]{1,16} placed an Arachne's Calling! Something is awakening! \(4/4\)""",
        RegexOption.IGNORE_CASE
    )

    private var triggerTimeMs: Long = -1L
    private var durationMs: Long = CRYSTAL_DURATION_MS

    val isActive: Boolean
        get() = triggerTimeMs > 0 && System.currentTimeMillis() - triggerTimeMs < durationMs

    private val remainingSeconds: Int
        get() = ((durationMs - (System.currentTimeMillis() - triggerTimeMs)) / 1000)
            .toInt().coerceAtLeast(0)

    fun register() {
        ClientReceiveMessageEvents.ALLOW_GAME.register { message, _ ->
            if (FamilyConfigManager.config.utilities.arachneTimer) {
                val plain = message.string.replace(COLOR_CODE_REGEX, "").trim()
                when {
                    plain.contains("You placed an Arachne Crystal") ||
                            plain.contains("Something is awakening") && !callingPattern.containsMatchIn(plain) -> {
                        triggerTimeMs = System.currentTimeMillis()
                        durationMs = CRYSTAL_DURATION_MS
                        notify("40s")
                    }
                    callingPattern.containsMatchIn(plain) -> {
                        triggerTimeMs = System.currentTimeMillis()
                        durationMs = CALLING_DURATION_MS
                        notify("20s")
                    }
                }
            }
            true
        }

        HudElementRegistry.addLast(
            Identifier.fromNamespaceAndPath("familyaddons", "arachne_timer"),
            HudElement { ctx, _ -> renderHud(ctx) }
        )
    }

    private fun renderHud(ctx: GuiGraphicsExtractor) {
        if (!isActive) return
        if (!FamilyConfigManager.config.utilities.arachneTimer) return

        val client = Minecraft.getInstance()
        val tr = client.font
        val secs = remainingSeconds

        val text = if (secs > 0) "§c§lArachne Spawns In: §e${secs}s" else "§c§lArachne Spawning NOW!"
        val textW = tr.width(text.replace(COLOR_CODE_REGEX, ""))

        val x = (ctx.guiWidth() - textW) / 2
        val y = ctx.guiHeight() / 4

        ctx.text(tr, Component.literal(text), x, y, -1, true)
    }

    private fun notify(duration: String) {
        Minecraft.getInstance().execute {
            Minecraft.getInstance().player?.sendSystemMessage(
                Component.literal("§6[FA] §aArachne Timer started — $duration")
            )
        }
    }
}