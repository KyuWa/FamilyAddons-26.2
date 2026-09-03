package org.kyowa.familyaddons.features

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import org.kyowa.familyaddons.COLOR_CODE_REGEX
import org.kyowa.familyaddons.config.FamilyConfigManager
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.resources.Identifier

object PickaxeAbility {

    private const val CROW_COOLDOWN_MS    = 31_000L
    private const val DEFAULT_COOLDOWN_MS = 36_000L

    private var endTimeMs = 0L

    const val PREVIEW_TEXT = "§bPickobulus §f31.00s"

    fun getScale() = FamilyConfigManager.config.mining.pickobulusHudScale
        .toFloatOrNull()?.coerceAtLeast(0.5f) ?: 1.5f

    fun resolvePos(sw: Int, sh: Int, scale: Float, textWidth: Int): Pair<Int, Int> {
        val cfg = FamilyConfigManager.config.mining
        return if (cfg.pickobulusHudX == -1 || cfg.pickobulusHudY == -1) {
            val x = ((sw - textWidth * scale) / 2f).toInt()
            val y = (sh / 2f + 40f).toInt()
            x to y
        } else {
            cfg.pickobulusHudX to cfg.pickobulusHudY
        }
    }

    private fun hasCrowPet(): Boolean {
        return try {
            val tabList = Minecraft.getInstance().connection?.onlinePlayers ?: return false
            tabList.any { entry ->
                val name = entry.tabListDisplayName?.string?.replace(COLOR_CODE_REGEX, "")?.trim() ?: return@any false
                name.contains("crow", ignoreCase = true)
            }
        } catch (e: Exception) { false }
    }

    fun register() {
        ClientReceiveMessageEvents.ALLOW_GAME.register { message, _ ->
            val plain = message.string.replace(COLOR_CODE_REGEX, "").trim()
            if (plain == "You used your Pickobulus Pickaxe Ability!") {
                if (!FamilyConfigManager.config.mining.pickobulusTimer) return@register true
                val cooldown = if (hasCrowPet()) CROW_COOLDOWN_MS else DEFAULT_COOLDOWN_MS
                endTimeMs = System.currentTimeMillis() + cooldown
            }
            true
        }

        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("familyaddons", "hud_pickaxeability_1"), HudElement { context, _ ->
            if (!FamilyConfigManager.config.mining.pickobulusTimer) return@HudElement
            val remaining = endTimeMs - System.currentTimeMillis()
            if (remaining <= 0) return@HudElement

            val client = Minecraft.getInstance()
            val tr = client.font
            val seconds = remaining / 1000.0
            val text = "§bPickobulus §f${"%.2f".format(seconds)}s"
            val scale = getScale()
            val tw = tr.width(text.replace(COLOR_CODE_REGEX, ""))
            val (x, y) = resolvePos(context.guiWidth(), context.guiHeight(), scale, tw)

            val matrices = context.pose()
            matrices.pushMatrix()
            matrices.translate(x.toFloat(), y.toFloat())
            matrices.scale(scale, scale)
            context.text(tr, Component.literal(text), 0, 0, -1, true)
            matrices.popMatrix()
        })
    }
}