package org.kyowa.familyaddons.commands

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.Style
import org.kyowa.familyaddons.config.FamilyConfigManager
import org.kyowa.familyaddons.config.TranslatorConfig
import org.kyowa.familyaddons.features.translator.ChatTranslator
import org.kyowa.familyaddons.features.translator.SlangDictionary
import org.kyowa.familyaddons.features.translator.TranslationService
import org.lwjgl.glfw.GLFW

/**
 * Player-facing translator commands:
 *
 *   /tr <text>   translate into the configured send language, click to copy
 *   /ptr <text>  translate and send to party chat
 *   /gtr <text>  translate and send to guild chat
 *
 * plus the hidden /fatranslate <id> that chat click events run, and the
 * /fa tr {reload,clear,status} maintenance subtree.
 */
object TranslateCommand {

    /** Hypixel chat cap; anything longer is rejected server-side. */
    private const val CHAT_LIMIT = 256

    fun register() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                literal("tr").then(argument("text", StringArgumentType.greedyString()).executes { ctx ->
                    translateOutgoing(StringArgumentType.getString(ctx, "text"), sendAs = null); 1
                })
            )
            dispatcher.register(
                literal("ptr").then(argument("text", StringArgumentType.greedyString()).executes { ctx ->
                    translateOutgoing(StringArgumentType.getString(ctx, "text"), sendAs = "pc"); 1
                })
            )
            dispatcher.register(
                literal("gtr").then(argument("text", StringArgumentType.greedyString()).executes { ctx ->
                    translateOutgoing(StringArgumentType.getString(ctx, "text"), sendAs = "gc"); 1
                })
            )
            // Chat click target. Not meant to be typed, but harmless if it is.
            dispatcher.register(
                literal(ChatTranslator.CLICK_COMMAND).then(argument("id", IntegerArgumentType.integer()).executes { ctx ->
                    val line = ChatTranslator.lookup(IntegerArgumentType.getInteger(ctx, "id"))
                    // The click event fires this synchronously, so the modifier
                    // state at this instant is the one the player clicked with.
                    val deep = deepModifierDown()
                    if (line == null) ChatTranslator.postSystem("§7That message is too old to translate.")
                    else ChatTranslator.translateAndPost(line, forced = true, deep = deep)
                    1
                })
            )
        }
    }

    /**
     * Ctrl or Alt held right now, read straight from GLFW like the HUD editor
     * does. Shift can't be the modifier: vanilla's handleComponentClicked
     * swallows shift-clicks for text insertion and never runs the click event.
     */
    private fun deepModifierDown(): Boolean {
        val window = Minecraft.getInstance().window.handle()
        return intArrayOf(GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL, GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT)
            .any { GLFW.glfwGetKey(window, it) == GLFW.GLFW_PRESS }
    }

    /** `/fa tr ...` — grafted onto the main command tree by TestCommand. */
    fun faSubtree(): LiteralArgumentBuilder<FabricClientCommandSource> =
        literal("tr")
            .executes {
                ChatTranslator.postSystem("§7/tr <text>§8, §7/ptr <text>§8, §7/gtr <text>§8, §7/fa tr reload§8|§7clear§8|§7status")
                1
            }
            .then(literal("reload").executes {
                SlangDictionary.reload()
                TranslationService.clearCache()
                ChatTranslator.postSystem("§aSlang dictionary reloaded.")
                1
            })
            .then(literal("clear").executes {
                TranslationService.clearCache()
                TranslationService.resetBackoff()
                ChatTranslator.postSystem("§aTranslation cache cleared.")
                1
            })
            .then(literal("status").executes {
                val cfg = FamilyConfigManager.config.translator
                val backend = if (cfg.backend == 0) "AI (${cfg.aiEndpoint.ifBlank { "§cno endpoint" }}§7) → Google fallback" else "Google"
                ChatTranslator.postSystem(
                    "§7enabled=§e${cfg.enabled} §7auto=§e${cfg.autoTranslate} §7click=§e${cfg.clickToTranslate} " +
                        "§7into=§e${TranslatorConfig.nameOf(cfg.targetLanguage)} §7send=§e${TranslatorConfig.nameOf(cfg.outgoingLanguage)} " +
                        "§7backend=§e$backend" + (TranslationService.lastError?.let { " §cpaused: $it" } ?: "")
                )
                1
            })

    /**
     * Translates the player's own text into the send language. With [sendAs]
     * null the result is shown locally with click-to-copy; otherwise it is
     * sent through that Hypixel chat command ("pc", "gc").
     */
    private fun translateOutgoing(text: String, sendAs: String?) {
        val cfg = FamilyConfigManager.config.translator
        val target = TranslatorConfig.codeOf(cfg.outgoingLanguage)
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        val extras = cfg.extraProtectedTerms.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

        TranslationService.translate(trimmed, target, extras) { result ->
            Minecraft.getInstance().execute {
                if (result == null) {
                    ChatTranslator.postSystem("§cCouldn't translate that." + (TranslationService.lastError?.let { " §7($it)" } ?: ""))
                    return@execute
                }
                val translated = result.text.take(CHAT_LIMIT)
                if (sendAs != null) {
                    Minecraft.getInstance().player?.connection?.sendCommand("$sendAs $translated")
                    return@execute
                }
                val component = ChatTranslator.prefixed("§8[${result.detected} → $target] §f$translated")
                    .withStyle { s: Style ->
                        s.withClickEvent(ClickEvent.CopyToClipboard(translated))
                            .withHoverEvent(HoverEvent.ShowText(ChatTranslator.gradient("Click to copy")))
                    }
                Minecraft.getInstance().player?.sendSystemMessage(component)
            }
        }
    }
}
