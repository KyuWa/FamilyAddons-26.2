package org.kyowa.familyaddons.features

import org.kyowa.familyaddons.COLOR_CODE_REGEX
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import org.kyowa.familyaddons.config.FamilyConfigManager
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.resources.Identifier

object DungeonDtTitle {

    private const val FADE_IN  = 10
    private const val HOLD     = 40
    private const val FADE_OUT = 10
    private const val TOTAL    = FADE_IN + HOLD + FADE_OUT

    private var titleText: String? = null
    private var titleTicks = 0

    const val PREVIEW_TEXT = "§e[MVP++] Player §crequested §fDT!"

    fun show(text: String) {
        if (!FamilyConfigManager.config.dungeons.dtTitle) return
        titleText = text
        titleTicks = TOTAL
    }

    fun tick() {
        if (titleTicks > 0) titleTicks--
    }

    fun getScale() = FamilyConfigManager.config.dungeons.dungeonDtTitleScale.toFloatOrNull()?.coerceAtLeast(1f) ?: 2f

    fun register() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("familyaddons", "hud_dungeondttitle_1"), HudElement { context, _ ->
            val text = titleText ?: return@HudElement
            if (titleTicks <= 0) return@HudElement
            if (!FamilyConfigManager.config.dungeons.dtTitle) return@HudElement

            val elapsed = TOTAL - titleTicks
            val alpha = when {
                elapsed < FADE_IN -> ((elapsed.toFloat() / FADE_IN) * 255).toInt()
                elapsed < FADE_IN + HOLD -> 255
                else -> ((1f - (elapsed - FADE_IN - HOLD).toFloat() / FADE_OUT) * 255).toInt()
            }.coerceIn(0, 255)
            if (alpha == 0) return@HudElement

            val client = Minecraft.getInstance()
            val renderer = client.font
            val scale = getScale()
            val sw = context.guiWidth()
            val sh = context.guiHeight()
            val plain = text.replace(COLOR_CODE_REGEX, "")
            val tw = renderer.width(plain)

            // Always centered, slightly above center — same as 1.21.10
            val x = ((sw - tw * scale) / 2f).toInt()
            val y = (sh / 2f - 30f * scale).toInt()
            val color = (alpha shl 24) or 0xFFFFFF

            val matrices = context.pose()
            matrices.pushMatrix()
            matrices.translate(x.toFloat(), y.toFloat())
            matrices.scale(scale, scale)
            context.text(renderer, Component.literal(text), 0, 0, color, true)
            matrices.popMatrix()
        })
    }
}