package org.kyowa.familyaddons.config

import com.google.gson.annotations.Expose
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorDraggableList
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorKeybind
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorText
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption
import org.lwjgl.glfw.GLFW

class ParkourConfig {
    @Expose @JvmField
    @ConfigOption(name = "Enable Parkour", desc = "Enable the parkour system.")
    @ConfigEditorBoolean
    var enabled = false

    @Expose @JvmField
    @ConfigOption(name = "Active Parkour", desc = "Name of the parkour to start when you press the keybind.")
    @ConfigEditorText
    var activeParkour = "default"

    @Expose @JvmField
    @ConfigOption(name = "Start Keybind", desc = "Keybind to start the active parkour.")
    @ConfigEditorKeybind(defaultKey = GLFW.GLFW_KEY_UNKNOWN)
    var startKey = GLFW.GLFW_KEY_UNKNOWN

    @Expose @JvmField
    @ConfigOption(name = "Developer Options", desc = "Enables editing commands (add, remove, clear, edit, resetbest). Off by default — turn on when building a parkour.")
    @ConfigEditorBoolean
    var developerMode = false
}
