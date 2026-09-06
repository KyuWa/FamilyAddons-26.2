package org.kyowa.familyaddons.features

import org.kyowa.familyaddons.util.FaChat

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.Items
import net.minecraft.network.chat.Component
import org.kyowa.familyaddons.config.FamilyConfigManager
import org.lwjgl.glfw.GLFW

object GfsKeybinds {

    // Track previous key states to detect press edges
    private var pearlWasDown = false
    private var superboomWasDown = false
    private var jerryWasDown = false
    private var decoyWasDown = false
    private var tapWasDown = false

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            // Don't fire if any screen is open (includes chat, inventory, etc.)
            if (client.gui.screen() != null) {
                pearlWasDown = false
                superboomWasDown = false
                jerryWasDown = false
                decoyWasDown = false
                tapWasDown = false
                return@register
            }

            val cfg = FamilyConfigManager.config.keybinds
            val handle = client.window.handle()

            fun isDown(key: Int) = key != GLFW.GLFW_KEY_UNKNOWN &&
                GLFW.glfwGetKey(handle, key) == GLFW.GLFW_PRESS

            val pearlDown = isDown(cfg.pearlKey)
            val superboomDown = isDown(cfg.superboomKey)
            val jerryDown = isDown(cfg.jerryKey)
            val decoyDown = isDown(cfg.decoyKey)
            val tapDown = isDown(cfg.tapKey)

            if (pearlDown && !pearlWasDown && cfg.pearlEnabled)
                gfs(client, "ENDER_PEARL", 16, useVanillaId = true)
            if (superboomDown && !superboomWasDown && cfg.superboomEnabled)
                gfs(client, "SUPERBOOM_TNT", 64)
            if (jerryDown && !jerryWasDown && cfg.jerryEnabled)
                gfs(client, "INFLATABLE_JERRY", 64)
            if (decoyDown && !decoyWasDown && cfg.decoyEnabled)
                gfs(client, "DECOY", 64)
            if (tapDown && !tapWasDown && cfg.tapEnabled)
                gfs(client, "TOXIC_ARROW_POISON", 192)

            pearlWasDown = pearlDown
            superboomWasDown = superboomDown
            jerryWasDown = jerryDown
            decoyWasDown = decoyDown
            tapWasDown = tapDown
        }
    }

    private fun gfs(client: Minecraft, sbId: String, max: Int, useVanillaId: Boolean = false) {
        val player = client.player ?: return
        var current = 0

        for (i in 0 until player.inventory.containerSize) {
            val stack = player.inventory.getItem(i)
            if (stack.isEmpty) continue

            val matches = if (useVanillaId) {
                stack.item == Items.ENDER_PEARL
            } else {
                getSkyblockId(stack) == sbId
            }

            if (matches) current += stack.count
        }

        val needed = max - current
        if (needed <= 0) {
            FaChat.send("§7Already at max stack size.")
            return
        }

        player.connection.sendChat("/gfs $sbId $needed")
    }

    private fun getSkyblockId(stack: net.minecraft.world.item.ItemStack): String? {
        val customData = stack.get(DataComponents.CUSTOM_DATA) ?: return null
        val nbt = customData.copyTag()
        return nbt.getString("id").orElse(null)?.ifBlank { null }
            ?: nbt.getCompoundOrEmpty("ExtraAttributes").getString("id").orElse(null)?.ifBlank { null }
    }

    private fun chat(player: net.minecraft.client.player.LocalPlayer, msg: String) {
        player.sendSystemMessage(Component.literal(msg))
    }
}
