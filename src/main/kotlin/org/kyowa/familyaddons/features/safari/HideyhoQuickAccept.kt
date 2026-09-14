package org.kyowa.familyaddons.features.safari

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import org.kyowa.familyaddons.COLOR_CODE_REGEX
import org.kyowa.familyaddons.config.FamilyConfigManager
import org.kyowa.familyaddons.util.FaChat

/**
 * Hideyho's "How about it?" prompt ends with `Select an option: [Sure] [No thanks…]`,
 * and the [Sure] is a small click target in a chat that keeps scrolling. With this
 * on, the prompt arms a one-shot: the next click anywhere on the screen while chat
 * is open runs [Sure]'s own click event, exactly as clicking the word would.
 *
 * One shot per run: it fires once and then stays disarmed until the tracker sees
 * you enter the Safari again, so a second prompt or a stray click cannot spam it.
 * The command executed is whatever Hypixel attached to [Sure]; nothing is typed.
 */
object HideyhoQuickAccept {

    @Volatile private var armed: ClickEvent? = null
    @Volatile private var usedThisRun = false
    private var lastMobSpeaker = ""

    private val MOB_LINE = Regex("""^\[MOB]\s+([A-Za-z ]+?):""")

    private fun enabled(): Boolean = FamilyConfigManager.config.safari.let { it.enabled && it.hideyhoQuickAccept }

    fun register() {
        ClientReceiveMessageEvents.ALLOW_GAME.register { message, overlay ->
            if (!overlay) runCatching { onChat(message) }
            true
        }
    }

    private fun onChat(message: Component) {
        if (!enabled()) return
        val plain = message.string.replace(COLOR_CODE_REGEX, "").trim()
        MOB_LINE.find(plain)?.let { lastMobSpeaker = it.groupValues[1].trim(); return }
        if (!plain.startsWith("Select an option:") || !plain.contains("[Sure]")) return
        if (!lastMobSpeaker.equals("Hideyho", ignoreCase = true)) return
        if (usedThisRun) return

        // The [Sure] leaf carries the click event; take it verbatim.
        val click = message.toFlatList()
            .firstOrNull { it.string.replace(COLOR_CODE_REGEX, "").trim() == "[Sure]" }
            ?.style?.clickEvent ?: return
        armed = click
        FaChat.send("§dHideyho§7: click anywhere while chat is open to pick §a[Sure]")
    }

    /**
     * Called from the chat screen's mouse handler. True when the click was consumed
     * by firing the armed [Sure].
     */
    fun onChatClick(): Boolean {
        if (!enabled()) return false
        val click = armed ?: return false
        if (usedThisRun) { armed = null; return false }
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return false
        val fired = when (click) {
            is ClickEvent.RunCommand -> { player.connection.sendCommand(click.command().removePrefix("/")); true }
            else -> false
        }
        if (!fired) return false
        usedThisRun = true
        armed = null
        FaChat.send("§aPicked §f[Sure] §7for Hideyho")
        return true
    }

    /** A new run re-arms it; called by [SafariTracker.reset]. */
    fun newRun() {
        armed = null
        usedThisRun = false
        lastMobSpeaker = ""
    }
}
