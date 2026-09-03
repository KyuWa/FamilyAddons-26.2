package org.kyowa.familyaddons.features

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.world.scores.Team
import net.minecraft.network.chat.Component
import org.kyowa.familyaddons.COLOR_CODE_REGEX
import org.kyowa.familyaddons.config.FamilyConfigManager
import org.lwjgl.glfw.GLFW

object DevTools {

    private var scoreboardWasDown = false
    private var tabListWasDown = false
    private var itemNbtWasDown = false
    private var copyRawWasDown = false

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            val cfg = FamilyConfigManager.config.dev
            val handle = client.window.handle()

            fun isDown(key: Int) = key != GLFW.GLFW_KEY_UNKNOWN &&
                    GLFW.glfwGetKey(handle, key) == GLFW.GLFW_PRESS

            val scoreboardDown = isDown(cfg.scoreboardKey)
            val tabListDown    = isDown(cfg.tabListKey)
            val itemNbtDown    = isDown(cfg.itemNbtKey)
            val copyRawDown    = isDown(cfg.copyRawChatKey)

            if (client.gui.screen() == null) {
                if (scoreboardDown && !scoreboardWasDown) grabScoreboard(client)
                if (tabListDown && !tabListWasDown) grabTabList(client)
                if (itemNbtDown && !itemNbtWasDown) grabItemNbt(client)
            }

            if (client.gui.screen() is ChatScreen) {
                if (copyRawDown && !copyRawWasDown) copyHoveredChat(client)
            }

            scoreboardWasDown = scoreboardDown
            tabListWasDown    = tabListDown
            itemNbtWasDown    = itemNbtDown
            copyRawWasDown    = copyRawDown
        }
    }

    fun getScoreboardLines(client: Minecraft): List<String> {
        val scoreboard = client.level?.scoreboard ?: return emptyList()
        val objective = scoreboard.getDisplayObjective(
            net.minecraft.world.scores.DisplaySlot.SIDEBAR
        ) ?: return emptyList()
        return scoreboard.listPlayerScores(objective)
            .filter { !it.isHidden() }
            .map { entry ->
                val team = scoreboard.getPlayersTeam(entry.owner())
                net.minecraft.world.scores.PlayerTeam.formatNameForTeam(team, entry.ownerName()).string.replace(COLOR_CODE_REGEX, "").trim()
            }
            .filter { it.isNotEmpty() }
    }

    private fun grabScoreboard(client: Minecraft) {
        val player = client.player ?: return
        val lines = getScoreboardLines(client)
        if (lines.isEmpty()) {
            chat(client, "§6[FA Dev] §cNo scoreboard entries found."); return
        }
        chat(client, "§6[FA Dev] §eScoreboard (${lines.size} lines):")
        lines.forEach { line ->
            player.sendSystemMessage(Component.literal("  §f$line"))
        }
        client.keyboardHandler.clipboard = lines.joinToString("\n")
        chat(client, "§6[FA Dev] §7Copied to clipboard.")
    }

    private fun grabTabList(client: Minecraft) {
        val player = client.player ?: return
        val tabList = client.connection?.onlinePlayers ?: run {
            chat(client, "§6[FA Dev] §cNo tab list found."); return
        }
        chat(client, "§6[FA Dev] §eTab list entries (${tabList.size}):")
        val sb = StringBuilder()
        tabList.forEach { entry ->
            val displayName = entry.tabListDisplayName?.string ?: "(no display name)"
            val profileName = entry.profile.name ?: "(no profile name)"
            val clean = displayName.replace(COLOR_CODE_REGEX, "")
            player.sendSystemMessage(Component.literal("  §7profile: §f$profileName §8| §7clean: §f$clean"))
            sb.appendLine("profile=$profileName | clean=$clean")
        }
        client.keyboardHandler.clipboard = sb.toString()
        chat(client, "§6[FA Dev] §7All entries copied to clipboard.")
    }

    private fun grabItemNbt(client: Minecraft) {
        val player = client.player ?: return
        val stack = player.mainHandItem
        if (stack.isEmpty) {
            chat(client, "§6[FA Dev] §cNo item in main hand."); return
        }
        val name = stack.hoverName.string.replace(COLOR_CODE_REGEX, "")
        chat(client, "§6[FA Dev] §eItem: §f$name §7| Count: §f${stack.count}")

        val customData = stack.get(DataComponents.CUSTOM_DATA)
        if (customData != null) {
            val nbt = customData.copyTag()
            chat(client, "§6[FA Dev] §eNBT keys:")
            nbt.keySet().forEach { key ->
                val value = nbt.get(key).toString()
                val truncated = if (value.length > 80) value.take(80) + "..." else value
                player.sendSystemMessage(Component.literal("  §e$key §8= §f$truncated"))
            }
            client.keyboardHandler.clipboard = nbt.toString()
            chat(client, "§6[FA Dev] §7Full NBT copied to clipboard.")
        } else {
            chat(client, "§6[FA Dev] §7No custom NBT on this item.")
        }
    }

    private fun copyHoveredChat(client: Minecraft) {
        // In 1.21.11, ChatHud does not expose getTextStyleAt publicly.
        // We instead grab the raw text of the most recently visible message
        // at the mouse position using the message list via reflection, or
        // fall back to copying the last received raw message string.
        try {
            // 26.2: Gui no longer exposes the ChatComponent — find it reflectively.
            val gui = client.gui
            val chatHud: Any = gui.javaClass.declaredFields
                .firstOrNull { net.minecraft.client.gui.components.ChatComponent::class.java.isAssignableFrom(it.type) }
                ?.let { f -> f.isAccessible = true; f.get(gui) }
                ?: run {
                    chat(client, "§6[FA Dev] §cCould not access chat HUD."); return
                }
            val mx = client.mouseHandler.getScaledXPos(client.window)
            val my = client.mouseHandler.getScaledYPos(client.window)

            // Try reflection to call getTextStyleAt if it exists under any name
            val method = chatHud.javaClass.methods.firstOrNull { m ->
                m.parameterCount == 2 &&
                        m.parameterTypes[0] == Double::class.java &&
                        m.parameterTypes[1] == Double::class.java
            }
            if (method != null) {
                val result = method.invoke(chatHud, mx, my)
                if (result != null) {
                    val raw = result.toString()
                    client.keyboardHandler.clipboard = raw
                    chat(client, "§6[FA Dev] §7Copied style: §f${raw.take(100)}")
                    return
                }
            }
            chat(client, "§6[FA Dev] §cNo hovered message found. Try hovering directly over a chat line.")
        } catch (e: Exception) {
            chat(client, "§6[FA Dev] §cCopy chat failed: ${e.message?.take(60)}")
        }
    }

    private fun chat(client: Minecraft, msg: String) {
        client.execute {
            client.player?.sendSystemMessage(Component.literal(msg))
        }
    }
}