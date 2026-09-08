package org.kyowa.familyaddons.features

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import org.kyowa.familyaddons.config.FamilyConfigManager
import org.kyowa.familyaddons.util.DevAccess
import org.kyowa.familyaddons.util.FaChat

/**
 * Renders the owner's name in the mod's own purple-to-dark gradient wherever a
 * component shows it: chat lines (MODIFY_GAME), the tab list and the nametag
 * above the head (mixins). Baked into every build so everyone running the
 * mod sees it; only the owner has a toggle (Dev > Name Style) and it only
 * affects their own screen. Nothing is sent anywhere, it is purely how this
 * client draws the text.
 */
object NameStyle {

    /** Names that get the gradient, lowercase. */
    private val STYLED = setOf("kyowaa")
    private val NAME_REGEX = Regex("""(?<![A-Za-z0-9_])(${STYLED.joinToString("|")})(?![A-Za-z0-9_])""", RegexOption.IGNORE_CASE)

    private fun enabled(): Boolean = !DevAccess.isDev() || FamilyConfigManager.config.dev.nameStyle

    /** Owner's Name Changer text ("&" colour codes allowed), or null when off / not the owner. */
    private fun customName(): String? {
        if (!DevAccess.isDev()) return null
        val t = FamilyConfigManager.config.dev.nameChanger.trim()
        return if (t.isEmpty()) null else t.replace(Regex("&([0-9a-fk-or])", RegexOption.IGNORE_CASE), "§$1")
    }

    private fun selfName(): String = net.minecraft.client.Minecraft.getInstance().user.name

    fun register() {
        ClientReceiveMessageEvents.MODIFY_GAME.register { message, _ -> restyle(message) }
    }

    /** Fast pre-check so the per-frame callers (tab list, nametags) stay cheap. */
    private fun mentions(text: String): Boolean {
        val lower = text.lowercase()
        return STYLED.any { lower.contains(it) }
    }

    /**
     * Returns [component] with every occurrence of a styled name recoloured,
     * keeping the surrounding text and the original click/hover/insertion so
     * clickable lines stay clickable. Returns the same instance when nothing
     * needs changing.
     */
    fun restyle(component: Component): Component {
        val custom = customName()
        if (!enabled() && custom == null) return component
        val whole = component.string
        val self = selfName()
        val selfMentioned = custom != null && whole.contains(self, ignoreCase = true)
        if (!mentions(whole) && !selfMentioned) return component

        val out: MutableComponent = Component.empty()
        var changed = false
        for (leaf in component.toFlatList()) {
            val text = leaf.string
            val style = leaf.style
            val hitsSelf = custom != null && text.contains(self, ignoreCase = true)
            if (!mentions(text) && !hitsSelf) { out.append(leaf); continue }
            val regex = if (custom != null) Regex("""(?<![A-Za-z0-9_])(${(STYLED + self.lowercase()).joinToString("|")})(?![A-Za-z0-9_])""", RegexOption.IGNORE_CASE) else NAME_REGEX
            var last = 0
            for (m in regex.findAll(text)) {
                if (m.range.first > last) out.append(Component.literal(text.substring(last, m.range.first)).withStyle(style))
                if (custom != null && m.value.equals(self, ignoreCase = true)) {
                    // Owner's screen only: the IGN shows as the Name Changer text.
                    out.append(Component.literal(custom).withStyle(style))
                } else {
                    out.append(gradient(m.value, style))
                }
                last = m.range.last + 1
                changed = true
            }
            if (last < text.length) out.append(Component.literal(text.substring(last)).withStyle(style))
        }
        return if (changed) out else component
    }

    /** The FaChat label gradient (dark at the ends, bright in the middle) on top of [base]. */
    private fun gradient(name: String, base: Style): MutableComponent {
        val out = Component.empty()
        val n = maxOf(1, name.length - 1)
        for (i in name.indices) {
            val k = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / n)
            val color = lerp(FaChat.GRADIENT_DARK, FaChat.GRADIENT_BRIGHT, k)
            out.append(Component.literal(name[i].toString()).withStyle(base.withColor(color)))
        }
        return out
    }

    private fun lerp(from: Int, to: Int, t: Double): Int {
        fun ch(shift: Int) = Math.round(((from shr shift) and 0xFF) + (((to shr shift) and 0xFF) - ((from shr shift) and 0xFF)) * t).toInt()
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
