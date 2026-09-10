package org.kyowa.familyaddons.features

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import org.kyowa.familyaddons.config.FamilyConfigManager

/**
 * Renders custom display names wherever a component shows a player's name:
 * chat lines (MODIFY_GAME), the tab list and nametags (mixins). Names come
 * from [NameSync] (approved templates, keyed by lowercase IGN).
 *
 * Template syntax (see NameSync.help): & / § legacy codes, <#rrggbb>,
 * <gradient:#a:#b>…</gradient>, <wave:#a:#b>…</wave>, <rainbow>…</rainbow>.
 *
 * Animation: chat lines are built once, so moving colours cannot live in the
 * component. Animated letters get a MARKER colour 0xFA'ii'ss (index, slot)
 * and NameStyleFontMixin swaps it for the live colour on every glyph drawn.
 */
object NameStyle {

    // ── lookup ──────────────────────────────────────────────────────────
    /** Approved shared names from the worker (lowercase IGN -> template). */
    @Volatile private var names: Map<String, String> = emptyMap()
    /** What actually gets replaced: shared names (if enabled) + the local nickname on top. */
    @Volatile private var effective: Map<String, String> = emptyMap()
    @Volatile private var regex: Regex? = null
    private val rendered = HashMap<String, Component>() // template -> component (Style.EMPTY base)
    private var lastKey = ""
    private var pollTicker = 0

    fun setNames(map: Map<String, String>) {
        names = map
        rebuild()
    }

    private fun enabled() = FamilyConfigManager.config.nameChanger.enabled

    /**
     * Local nickname, plain text: only this client sees it, in place of the
     * player's own IGN. Colour codes and tags are stripped so it stays plain.
     */
    fun localNick(): String = visible(FamilyConfigManager.config.nameChanger.localNick)
        .filter { it >= ' ' }.trim().take(MAX_VISIBLE)

    private fun rebuild() {
        val map = HashMap<String, String>()
        if (enabled()) map.putAll(names)
        val nick = localNick()
        val me = Minecraft.getInstance().user?.name
        if (nick.isNotEmpty() && !me.isNullOrEmpty()) map[me.lowercase()] = nick
        effective = map
        regex = if (map.isEmpty()) null else
            // Word boundary on both sides, except that a legacy colour code counts as a
            // boundary: Hypixel writes "§bzMusu §eis in..." (friend list, guild list)
            // with no space between the code letter and the name.
            Regex("(?<!(?<!§)[A-Za-z0-9_])(" + map.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + ")(?![A-Za-z0-9_])", RegexOption.IGNORE_CASE)
        synchronized(rendered) { rendered.clear() }
    }

    fun register() {
        ClientReceiveMessageEvents.MODIFY_GAME.register { message, _ -> restyle(message) }
        // Config edits (toggle / nickname) take effect within a second without a rejoin.
        ClientTickEvents.END_CLIENT_TICK.register {
            if (++pollTicker < 20) return@register
            pollTicker = 0
            val key = "${enabled()}|${localNick()}|${Minecraft.getInstance().user?.name}"
            if (key != lastKey) { lastKey = key; rebuild() }
        }
    }

    /** Fast pre-check so the per-frame callers stay cheap. */
    private fun mentions(text: String): Boolean {
        val map = effective
        if (map.isEmpty()) return false
        val lower = text.lowercase()
        for (k in map.keys) if (lower.contains(k)) return true
        return false
    }

    /**
     * [component] with every known IGN replaced by that player's rendered
     * template, keeping surrounding text and the leaf style (click / hover /
     * insertion) so clickable lines stay clickable. Same instance when
     * nothing matched.
     */
    fun restyle(component: Component): Component {
        val rx = regex ?: return component
        if (!mentions(component.string)) return component
        val out: MutableComponent = Component.empty()
        var changed = false
        for (leaf in component.toFlatList()) {
            val text = leaf.string
            val style = leaf.style
            if (!mentions(text)) { out.append(leaf); continue }
            var last = 0
            for (m in rx.findAll(text)) {
                val template = effective[m.value.lowercase()] ?: continue
                if (m.range.first > last) out.append(Component.literal(text.substring(last, m.range.first)).withStyle(legacyStyle(style, text, last)))
                // Hypixel colours chat with § codes inside one string; the code that was
                // active where the name sat (rank colour) must carry into the new piece.
                out.append(render(template, legacyStyle(style, text, m.range.first)))
                last = m.range.last + 1
                changed = true
            }
            if (last < text.length) out.append(Component.literal(text.substring(last)).withStyle(legacyStyle(style, text, last)))
        }
        return if (changed) out else component
    }

    /** [base] plus whatever legacy § codes are in force at [end] of [text]. */
    private fun legacyStyle(base: Style, text: String, end: Int): Style {
        var s = base
        var i = 0
        while (i < end - 1) {
            if (text[i] == '§') {
                val c = text[i + 1].lowercaseChar()
                LEGACY[c]?.let { s = s.withColor(it).withBold(false).withItalic(false).withUnderlined(false).withStrikethrough(false).withObfuscated(false) }
                when (c) {
                    'k' -> s = s.withObfuscated(true)
                    'l' -> s = s.withBold(true)
                    'm' -> s = s.withStrikethrough(true)
                    'n' -> s = s.withUnderlined(true)
                    'o' -> s = s.withItalic(true)
                    'r' -> s = base
                }
                i += 2
            } else i++
        }
        return s
    }

    // ── template engine ─────────────────────────────────────────────────

    private const val MAX_VISIBLE = 24
    private const val MAX_RAW = 400
    // <gradient|wave|rainbow:#a:#b:#c...> any number of colour stops (0, 2, 3, ...).
    private val TAG = Regex("""<(/?)(gradient|wave|rainbow)((?::#[0-9a-fA-F]{6})*)>|<#([0-9a-fA-F]{6})>|[&§]([0-9a-fk-orA-FK-OR])""")
    private val STOP = Regex("""#([0-9a-fA-F]{6})""")

    /** Visible characters only (tags and codes stripped). */
    fun visible(template: String): String = template.replace(TAG, "")

    /** Null when ok, else why not. Mirrors the worker's checks. */
    fun validate(template: String): String? {
        if (template.length > MAX_RAW) return "Template longer than $MAX_RAW characters."
        if (template.any { it < ' ' }) return "Control characters are not allowed."
        val v = visible(template)
        if (v.isBlank()) return "Nothing visible."
        if (v.length > MAX_VISIBLE) return "Visible text is ${v.length} characters, max $MAX_VISIBLE."
        return null
    }

    private enum class AnimType { WAVE, RAINBOW }
    private class Anim(val type: AnimType, val stops: IntArray, val n: Int)
    private val anims = ArrayList<Anim>()          // slot -> anim, capped at 255
    private const val MARKER_RED = 0xFA
    private const val PERIOD_MS = 2400.0

    private fun slotFor(type: AnimType, stops: IntArray, n: Int): Int {
        synchronized(anims) {
            for ((i, x) in anims.withIndex()) if (x.type == type && x.stops.contentEquals(stops) && x.n == n) return i
            if (anims.size >= 255) return -1
            anims.add(Anim(type, stops, n)); return anims.size - 1
        }
    }

    private val LEGACY = mapOf(
        '0' to 0x000000, '1' to 0x0000AA, '2' to 0x00AA00, '3' to 0x00AAAA, '4' to 0xAA0000, '5' to 0xAA00AA,
        '6' to 0xFFAA00, '7' to 0xAAAAAA, '8' to 0x555555, '9' to 0x5555FF, 'a' to 0x55FF55, 'b' to 0x55FFFF,
        'c' to 0xFF5555, 'd' to 0xFF55FF, 'e' to 0xFFFF55, 'f' to 0xFFFFFF,
    )

    /** Render a template on top of [base] (the original leaf style: keeps click/hover). Cached per template for the empty base. */
    fun render(template: String, base: Style): Component {
        if (base == Style.EMPTY) synchronized(rendered) { rendered[template]?.let { return it } }
        val c = build(template, base)
        if (base == Style.EMPTY) synchronized(rendered) { rendered[template] = c }
        return c
    }

    private fun build(template: String, base: Style): Component {
        val out = Component.empty()
        var color: Int? = null
        var bold = false; var italic = false; var underline = false; var strike = false; var obf = false
        // open animated/gradient run: (null type = static gradient), colour stops
        var run: Pair<AnimType?, IntArray>? = null
        val runText = StringBuilder()
        var runStyleSnapshot: Style? = null

        fun style(): Style {
            var s = base.withBold(bold).withItalic(italic).withUnderlined(underline).withStrikethrough(strike).withObfuscated(obf)
            if (color != null) s = s.withColor(color!!)
            return s
        }
        fun flushRun() {
            val r = run ?: return
            val text = runText.toString()
            val n = text.length
            val st = runStyleSnapshot ?: style()
            if (n > 0) {
                if (r.first == null) {
                    for ((i, ch) in text.withIndex()) {
                        val k = if (n == 1) 0.0 else i.toDouble() / (n - 1)
                        out.append(Component.literal(ch.toString()).withStyle(st.withColor(lerpN(r.second, k))))
                    }
                } else {
                    val slot = slotFor(r.first!!, r.second, n)
                    for ((i, ch) in text.withIndex()) {
                        val col = if (slot < 0) lerpN(r.second, i.toDouble() / maxOf(1, n - 1))
                                  else (MARKER_RED shl 16) or ((i and 0xFF) shl 8) or (slot and 0xFF)
                        out.append(Component.literal(ch.toString()).withStyle(st.withColor(col)))
                    }
                }
            }
            run = null; runText.setLength(0); runStyleSnapshot = null
        }
        fun emit(text: String) {
            if (text.isEmpty()) return
            if (run != null) runText.append(text) else out.append(Component.literal(text).withStyle(style()))
        }

        var last = 0
        for (m in TAG.findAll(template)) {
            emit(template.substring(last, m.range.first))
            last = m.range.last + 1
            val g = m.groupValues
            when {
                g[2].isNotEmpty() -> { // <gradient|wave|rainbow ...> or closing
                    if (g[1] == "/") { flushRun(); continue }
                    flushRun()
                    val given = STOP.findAll(g[3]).map { it.groupValues[1].toInt(16) }.toList()
                    val kind = g[2].lowercase()
                    // gradient / wave need at least two stops (defaults red -> blue); rainbow
                    // with no stops is the hue sweep, with stops it cycles through them.
                    val stops = when {
                        given.size >= 2 -> given.toIntArray()
                        kind == "rainbow" -> IntArray(0)
                        given.size == 1 -> intArrayOf(given[0], given[0])
                        else -> intArrayOf(0xFF0000, 0x0000FF)
                    }
                    run = when (kind) {
                        "gradient" -> Pair(null, stops)
                        "wave" -> Pair(AnimType.WAVE, stops)
                        else -> Pair(AnimType.RAINBOW, stops)
                    }
                    runStyleSnapshot = style()
                }
                g[4].isNotEmpty() -> { flushRun(); color = g[4].toInt(16) }
                g[5].isNotEmpty() -> {
                    val code = g[5].lowercase()[0]
                    when (code) {
                        'l' -> bold = true; 'o' -> italic = true; 'n' -> underline = true; 'm' -> strike = true; 'k' -> obf = true
                        'r' -> { flushRun(); color = null; bold = false; italic = false; underline = false; strike = false; obf = false }
                        else -> LEGACY[code]?.let { flushRun(); color = it; bold = false; italic = false; underline = false; strike = false; obf = false }
                    }
                }
            }
        }
        emit(template.substring(last))
        flushRun()
        return out
    }

    // ── animation (font hook) ───────────────────────────────────────────

    private fun animated(): Boolean = FamilyConfigManager.config.nameChanger.animate

    /** Font hook: a marker-coloured style becomes the current animated colour; anything else passes through. */
    fun animate(style: Style): Style {
        val tc = style.color ?: return style
        val v = tc.value
        if ((v shr 16) and 0xFF != MARKER_RED) return style
        val i = (v shr 8) and 0xFF
        val slot = v and 0xFF
        val anim = synchronized(anims) { anims.getOrNull(slot) } ?: return style
        if (i >= anim.n) return style
        val pos = i.toDouble() / maxOf(1, anim.n - 1)
        val phase = if (animated()) (System.currentTimeMillis() % PERIOD_MS.toLong()) / PERIOD_MS else 0.0
        val col = when (anim.type) {
            // Sweeps across every stop and back, so a 3-colour wave shows a -> b -> c -> b -> a.
            AnimType.WAVE -> lerpN(anim.stops, 0.5 - 0.5 * Math.cos(2 * Math.PI * (pos - phase)))
            AnimType.RAINBOW ->
                if (anim.stops.size >= 2) lerpCycle(anim.stops, (pos * 0.6 + phase) % 1.0)
                else java.awt.Color.HSBtoRGB(((pos * 0.6 + phase) % 1.0).toFloat(), 0.85f, 1f) and 0xFFFFFF
        }
        return style.withColor(col)
    }

    /** Colour at [t] in 0..1 along a chain of stops (a -> b -> c ...). */
    private fun lerpN(stops: IntArray, t: Double): Int {
        if (stops.isEmpty()) return 0xFFFFFF
        if (stops.size == 1) return stops[0]
        val k = t.coerceIn(0.0, 1.0) * (stops.size - 1)
        val i = minOf(k.toInt(), stops.size - 2)
        return lerp(stops[i], stops[i + 1], k - i)
    }

    /** Like [lerpN] but the chain loops back to the first stop (a -> b -> c -> a). */
    private fun lerpCycle(stops: IntArray, t: Double): Int {
        val loop = stops + stops[0]
        return lerpN(loop, ((t % 1.0) + 1.0) % 1.0)
    }

    private fun lerp(from: Int, to: Int, t: Double): Int {
        val k = t.coerceIn(0.0, 1.0)
        fun ch(shift: Int) = Math.round(((from shr shift) and 0xFF) + (((to shr shift) and 0xFF) - ((from shr shift) and 0xFF)) * k).toInt()
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
