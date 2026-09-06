package org.kyowa.familyaddons.util

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style

/**
 * The mod's chat identity: every line FamilyAddons prints starts with `[FA]`
 * drawn in the same purple-to-dark sweep FamilyStorage uses for its label, so
 * players can tell at a glance which mod is talking.
 *
 * Use [prefixed] instead of `Component.literal("§6[FA] …")`; legacy § codes
 * inside the body still render, so existing message strings work unchanged.
 */
object FaChat {

    private const val TAG = "[FA]"

    // Same purple-to-dark gradient FamilyStorage uses for its label.
    const val GRADIENT_BRIGHT = 0xC86EFF // (200, 110, 255)
    const val GRADIENT_DARK = 0x4B147D   // (75, 20, 125)

    /**
     * Text coloured character by character, dark at the ends and bright in the
     * middle — the static form of FamilyStorage's sweeping label gradient
     * (chat components cannot animate).
     */
    fun gradient(text: String): MutableComponent {
        val out = Component.empty()
        val n = maxOf(1, text.length - 1)
        for (i in text.indices) {
            val k = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / n)
            out.append(Component.literal(text[i].toString()).withStyle(Style.EMPTY.withColor(lerpColor(GRADIENT_DARK, GRADIENT_BRIGHT, k))))
        }
        return out
    }

    private fun lerpColor(from: Int, to: Int, t: Double): Int {
        fun ch(shift: Int) = Math.round(((from shr shift) and 0xFF) + (((to shr shift) and 0xFF) - ((from shr shift) and 0xFF)) * t).toInt()
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** `[FA] ` in the gradient, followed by [body]. */
    fun prefixed(body: Component): MutableComponent = gradient(TAG).append(Component.literal(" ")).append(body)
    fun prefixed(text: String): MutableComponent = prefixed(Component.literal(text))

    /** Convenience: send a prefixed line to the local player's chat. Safe to call from any thread. */
    fun send(text: String) {
        val mc = Minecraft.getInstance()
        mc.execute { mc.player?.sendSystemMessage(prefixed(text)) }
    }
}
