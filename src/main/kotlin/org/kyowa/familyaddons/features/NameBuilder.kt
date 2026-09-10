package org.kyowa.familyaddons.features

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import org.kyowa.familyaddons.config.FamilyConfigManager
import org.kyowa.familyaddons.util.FaChat

/**
 * Name Changer "Easy Builder": turns the colour-wheel picks, a style and a
 * few toggles into the template the engine understands, so nobody has to
 * type hex codes. The result lands in My Name; Submit sends it as usual.
 */
object NameBuilder {

    private const val MAX_VISIBLE = 24

    /** "chroma:alpha:r:g:b" (MoulConfig colour editor) -> "#rrggbb". */
    private fun hex(colour: String): String {
        val p = colour.split(":")
        return try {
            "#%02x%02x%02x".format(p[2].toInt().coerceIn(0, 255), p[3].toInt().coerceIn(0, 255), p[4].toInt().coerceIn(0, 255))
        } catch (_: Exception) { "#ffffff" }
    }

    fun build(): String {
        val cfg = FamilyConfigManager.config.nameChanger
        val ign = Minecraft.getInstance().user?.name ?: ""
        // Plain text only: anything that could be read as a code is dropped.
        val text = cfg.builderText.filter { it >= ' ' && it != '<' && it != '>' && it != '&' && it != '§' }
            .trim().ifEmpty { ign }.take(MAX_VISIBLE)
        val fmt = buildString {
            if (cfg.builderBold) append("&l")
            if (cfg.builderItalic) append("&o")
            if (cfg.builderUnderline) append("&n")
        }
        val c1 = hex(cfg.builderColor1)
        val c2 = hex(cfg.builderColor2)
        val stops = if (cfg.builderUseColor3) "$c1:$c2:${hex(cfg.builderColor3)}" else "$c1:$c2"
        return when (cfg.builderStyle) {
            0 -> "$fmt<$c1>$text"
            1 -> "$fmt<gradient:$stops>$text</gradient>"
            2 -> "$fmt<wave:$stops>$text</wave>"
            3 -> "$fmt<rainbow>$text</rainbow>"
            else -> "$fmt<rainbow:$stops>$text</rainbow>"
        }
    }

    /** Build button: write the template into My Name, save, and show it. */
    fun apply() {
        val template = build()
        NameStyle.validate(template)?.let { FaChat.send("§c$it"); return }
        FamilyConfigManager.config.nameChanger.myName = template
        FamilyConfigManager.save()
        val mc = Minecraft.getInstance()
        mc.execute {
            mc.player?.sendSystemMessage(
                FaChat.prefixed(Component.literal("§7Built: ").append(NameStyle.render(template, Style.EMPTY))
                    .append(Component.literal(" §8- it is in My Name now; press Submit to send it for approval.")))
            )
        }
    }
}
