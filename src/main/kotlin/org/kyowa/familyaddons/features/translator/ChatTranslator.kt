package org.kyowa.familyaddons.features.translator

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import org.kyowa.familyaddons.COLOR_CODE_REGEX
import org.kyowa.familyaddons.config.FamilyConfigManager
import org.kyowa.familyaddons.config.TranslatorConfig
import org.kyowa.familyaddons.util.FaChat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hooks incoming chat for the translator.
 *
 * Every player line gets a click event (translate on demand) and, if
 * auto-translate is on for its channel, is also translated straight away.
 * Translations are posted as a separate line beneath the original rather
 * than rewriting it, so the original is never lost and nothing has to block
 * on the network.
 */
object ChatTranslator {

    /** Plain-text form of the mod prefix, used to recognise our own lines and skip them. */
    private const val PLAIN_PREFIX = "[FA] "

    // The mod-wide gradient prefix lives in FaChat; these keep call sites short.
    fun gradient(text: String): MutableComponent = FaChat.gradient(text)
    fun prefixed(text: String): MutableComponent = FaChat.prefixed(text)

    /** Hidden client command the chat click event runs. */
    const val CLICK_COMMAND = "fatranslate"

    enum class Channel { PARTY, GUILD, COOP, DM, PUBLIC }

    data class ChatLine(val channel: Channel, val sender: String, val body: String)

    // ── Hypixel chat shapes ───────────────────────────────────────────
    // "From [MVP+] Name: hi" / "To Name: hi"
    private val DM_REGEX = Regex("""^(From|To)\s+(?:\[[^\]]+\]\s*)?([A-Za-z0-9_]{1,16}):\s(.+)$""")
    // "Party > [MVP+] Name: hi" / "Guild > Name [GM]: hi" / "Officer > ..." / "Co-op > ..."
    private val CHANNEL_REGEX = Regex("""^(Party|Guild|Officer|Co-op)\s*[>»]\s*(?:\[[^\]]+\]\s*)?([A-Za-z0-9_]{1,16})(?:\s*\[[^\]]+\])?:\s(.+)$""")
    // "[123] ⚒ [MVP+] Name ✫: hi" — level, symbol, rank and emblem are all optional.
    private val PUBLIC_REGEX = Regex("""^(?:\[\d+\]\s*)?(?:[^\w\s\[\]]\s*)?(?:\[[^\]]+\]\s*)?([A-Za-z0-9_]{1,16})(?:\s*[^\w\s:\[\]]+)?:\s(.+)$""")

    // ── Recent lines, so a click can find what to translate ──────────
    private const val HISTORY_SIZE = 200
    private val history = ConcurrentHashMap<Int, ChatLine>()
    private val nextId = AtomicInteger(0)

    fun register() {
        ClientReceiveMessageEvents.MODIFY_GAME.register { message, overlay ->
            if (overlay) return@register message
            val cfg = FamilyConfigManager.config.translator
            if (!cfg.enabled) return@register message

            val plain = message.string.replace(COLOR_CODE_REGEX, "").trim()
            if (plain.startsWith(PLAIN_PREFIX)) return@register message
            val line = parse(plain) ?: return@register message

            val id = remember(line)

            if (cfg.autoTranslate && shouldAutoTranslate(line, cfg)) {
                translateAndPost(line, forced = false)
            }

            if (!cfg.clickToTranslate) return@register message
            message.copy().withStyle { s: Style ->
                // Children carrying their own click (player name menus etc.)
                // keep it; only the plain text of the line picks ours up.
                if (s.clickEvent != null) s
                else s.withClickEvent(ClickEvent.RunCommand("/$CLICK_COMMAND $id"))
                    .withHoverEvent(HoverEvent.ShowText(
                        gradient("Click to translate").append(Component.literal("\n§8Ctrl-click: slower, explains the slang"))
                    ))
            }
        }
    }

    // ── Parsing ──────────────────────────────────────────────────────

    fun parse(plain: String): ChatLine? {
        DM_REGEX.find(plain)?.let { m ->
            return ChatLine(Channel.DM, m.groupValues[2], m.groupValues[3].trim())
        }
        CHANNEL_REGEX.find(plain)?.let { m ->
            val channel = when (m.groupValues[1]) {
                "Party" -> Channel.PARTY
                "Co-op" -> Channel.COOP
                else -> Channel.GUILD
            }
            return ChatLine(channel, m.groupValues[2], m.groupValues[3].trim())
        }
        PUBLIC_REGEX.find(plain)?.let { m ->
            return ChatLine(Channel.PUBLIC, m.groupValues[1], m.groupValues[2].trim())
        }
        return null
    }

    private fun remember(line: ChatLine): Int {
        val id = nextId.incrementAndGet()
        history[id] = line
        // Cheap eviction: ids are monotonic, so anything older than the window goes.
        val cutoff = id - HISTORY_SIZE
        if (cutoff > 0) history.keys.removeIf { it <= cutoff }
        return id
    }

    fun lookup(id: Int): ChatLine? = history[id]

    // ── Filtering ────────────────────────────────────────────────────

    private fun shouldAutoTranslate(line: ChatLine, cfg: TranslatorConfig): Boolean {
        val channelOn = when (line.channel) {
            Channel.PARTY -> cfg.channelParty
            Channel.GUILD -> cfg.channelGuild
            Channel.COOP -> cfg.channelCoop
            Channel.DM -> cfg.channelDm
            Channel.PUBLIC -> cfg.channelPublic
        }
        if (!channelOn) return false
        if (!cfg.translateOwn && line.sender.equals(selfName(), ignoreCase = true)) return false
        return isWorthTranslating(line.body, cfg)
    }

    private fun isWorthTranslating(body: String, cfg: TranslatorConfig): Boolean {
        if (body.length > cfg.maxLength.toInt()) return false
        if (body.startsWith("/")) return false
        // Needs at least a couple of letters — "?", "!!!", "1" are not language.
        return body.count { it.isLetter() } >= 2
    }

    private fun selfName() = Minecraft.getInstance().player?.name?.string ?: ""

    /**
     * Player names in the tab list are masked from the translator alongside
     * the config's extra terms, so "carry me Kyowa" does not come back with
     * a translated username.
     */
    private fun protectedExtras(): Set<String> {
        val cfg = FamilyConfigManager.config.translator
        val out = HashSet<String>()
        cfg.extraProtectedTerms.split(',').map { it.trim().lowercase() }.filterTo(out) { it.isNotEmpty() }
        Minecraft.getInstance().connection?.onlinePlayers?.forEach { entry ->
            entry.profile.name?.takeIf { it.length >= 3 }?.let { out.add(it.lowercase()) }
        }
        return out
    }

    // ── Translate + post ─────────────────────────────────────────────

    /**
     * @param forced true for click / command requests: skips the same-language
     *        and source-filter checks and always says something back.
     */
    /**
     * @param deep ctrl-click: slower AI-only pass that also explains the slang.
     */
    fun translateAndPost(line: ChatLine, forced: Boolean, deep: Boolean = false) {
        val cfg = FamilyConfigManager.config.translator
        val target = TranslatorConfig.codeOf(cfg.targetLanguage)
        val extras = protectedExtras()

        TranslationService.translate(line.body, target, extras, deep) { result ->
            Minecraft.getInstance().execute {
                if (result == null) {
                    if (forced) postSystem("§cCouldn't translate that." + (TranslationService.lastError?.let { " §7($it)" } ?: ""))
                    return@execute
                }
                val detected = result.detected.lowercase()
                if (!forced) {
                    if (cfg.skipSameLanguage && sameLanguage(detected, target)) return@execute
                    val filter = cfg.sourceFilter.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                    if (filter.isNotEmpty() && detected !in filter) return@execute
                } else if (!deep && sameLanguage(detected, target) && result.text.equals(line.body, ignoreCase = true)) {
                    postSystem("§7That's already in ${TranslatorConfig.nameOf(cfg.targetLanguage)}.")
                    return@execute
                }
                postTranslation(line, result, target, cfg)
            }
        }
    }

    private fun sameLanguage(detected: String, target: String): Boolean =
        detected.substringBefore('-') == target.lowercase().substringBefore('-')

    private fun postTranslation(line: ChatLine, result: TranslationService.Result, target: String, cfg: TranslatorConfig) {
        val text = StringBuilder()
        if (cfg.showLanguageTag) text.append("§8[${result.detected} → $target] ")
        if (cfg.showSender) text.append("§e${line.sender}§7: ")
        text.append("§f").append(result.text)
        if (cfg.markUncertain && result.uncertain) text.append(" §8(?)")

        val component = prefixed(text.toString())
        if (cfg.hoverOriginal) {
            val hover = gradient("Original").append(Component.literal("§7: §f${line.body}"))
            component.withStyle { s: Style -> s.withHoverEvent(HoverEvent.ShowText(hover)) }
        }
        val player = Minecraft.getInstance().player ?: return
        player.sendSystemMessage(component)
        // Deep mode: the slang breakdown goes on its own line under the translation.
        result.notes?.let { player.sendSystemMessage(Component.literal("   §8↳ §7$it")) }
    }

    fun postSystem(text: String) {
        Minecraft.getInstance().player?.sendSystemMessage(prefixed(text))
    }
}
