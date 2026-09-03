package org.kyowa.familyaddons.features

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket
import net.minecraft.network.chat.Component
import org.kyowa.familyaddons.FamilyAddons
import org.kyowa.familyaddons.config.FamilyConfigManager
import org.kyowa.familyaddons.util.MathEval

object SignMath {

    fun register() {
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen !is AbstractSignEditScreen) return@register
            ScreenEvents.afterExtract(screen).register { s, context, _, _, _ ->
                if (!FamilyConfigManager.config.utilities.signMath) return@register

                // Get the current text lines via reflection — scan all fields for String[]
                val lines = try {
                    var found: Array<String>? = null
                    var cls: Class<*>? = s.javaClass
                    while (cls != null && found == null) {
                        for (f in cls.declaredFields) {
                            f.isAccessible = true
                            val v = f.get(s)
                            if (v is Array<*> && v.isArrayOf<String>()) {
                                @Suppress("UNCHECKED_CAST")
                                val arr = v as Array<String>
                                if (arr.size in 1..4) { found = arr; break }
                            }
                        }
                        cls = cls.superclass
                    }
                    found
                } catch (e: Exception) { null } ?: return@register

                val line = lines.getOrNull(0)?.trim() ?: return@register
                if (line.isEmpty()) return@register

                val result = MathEval.evaluate(line)
                val sw = context.guiWidth()
                val client = net.minecraft.client.Minecraft.getInstance()
                val tr = client.font

                val (msg, color) = if (result != null) {
                    val formatted = if (result == Math.floor(result) && result.isFinite())
                        result.toLong().toString()
                    else "%.2f".format(result)
                    if (formatted.length > 15)
                        "$line = $formatted (too long!)" to 0xFFFFAA00.toInt()
                    else
                        "$line = $formatted" to 0xFF55FF55.toInt()
                } else {
                    "Invalid expression" to 0xFFFF5555.toInt()
                }

                val tw = tr.width(msg)
                context.text(tr, Component.literal(msg), (sw - tw) / 2, 15, color, true)
            }
        }
    }

    fun handleSignPacket(packet: ServerboundSignUpdatePacket): ServerboundSignUpdatePacket? {
        if (!FamilyConfigManager.config.utilities.signMath) return null

        val lines = packet.lines.toMutableList()
        var changed = false

        for (i in lines.indices) {
            val line = lines[i].trim()
            if (line.isEmpty()) continue

            val arrowMatch = Regex(""">\\s*([^<]+?)\\s*<""").find(line)
            if (arrowMatch != null) {
                val result = MathEval.evaluate(arrowMatch.groupValues[1].trim()) ?: continue
                val formatted = formatResult(result)
                val replaced = line.replace(arrowMatch.value, "> $formatted <")
                if (replaced.length > 15) continue // too long for a sign line
                lines[i] = replaced
                changed = true
                break
            }

            val result = MathEval.evaluate(line) ?: continue
            val formatted = formatResult(result)
            if (formatted.length > 15) continue // too long for a sign line
            lines[i] = formatted
            changed = true
            break
        }

        if (!changed) return null

        return try {
            ServerboundSignUpdatePacket(
                packet.pos, packet.isFrontText,
                lines.getOrElse(0) { "" },
                lines.getOrElse(1) { "" },
                lines.getOrElse(2) { "" },
                lines.getOrElse(3) { "" }
            )
        } catch (e: Exception) {
            FamilyAddons.LOGGER.warn("SignMath: ${e.message}")
            null
        }
    }

    private fun formatResult(value: Double): String =
        if (value == value.toLong().toDouble() && value.isFinite()) value.toLong().toString()
        else "%.2f".format(value)
}
