package org.kyowa.familyaddons.config

import com.google.gson.annotations.Expose
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorKeybind
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption
import org.lwjgl.glfw.GLFW

class DevConfig {

    @Expose @JvmField
    @ConfigOption(name = "Grab Scoreboard", desc = "Press to print all sidebar scoreboard entries to chat.")
    @ConfigEditorKeybind(defaultKey = GLFW.GLFW_KEY_UNKNOWN)
    var scoreboardKey = GLFW.GLFW_KEY_UNKNOWN

    @Expose @JvmField
    @ConfigOption(name = "Grab Tab List", desc = "Press to print all tab list entries to chat.")
    @ConfigEditorKeybind(defaultKey = GLFW.GLFW_KEY_UNKNOWN)
    var tabListKey = GLFW.GLFW_KEY_UNKNOWN

    @Expose @JvmField
    @ConfigOption(name = "Grab Item NBT", desc = "Press while holding an item to print its full NBT to chat.")
    @ConfigEditorKeybind(defaultKey = GLFW.GLFW_KEY_UNKNOWN)
    var itemNbtKey = GLFW.GLFW_KEY_UNKNOWN

    @Expose @JvmField
    @ConfigOption(name = "Copy Raw Chat", desc = "Press while hovering over a chat message to copy its raw text.")
    @ConfigEditorKeybind(defaultKey = GLFW.GLFW_KEY_UNKNOWN)
    var copyRawChatKey = GLFW.GLFW_KEY_UNKNOWN
}
