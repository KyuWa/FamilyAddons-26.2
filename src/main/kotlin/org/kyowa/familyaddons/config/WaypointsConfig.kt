package org.kyowa.familyaddons.config

import com.google.gson.annotations.Expose
import io.github.notenoughupdates.moulconfig.annotations.*
import org.lwjgl.glfw.GLFW

class WaypointsConfig {
    @Expose @JvmField
    @ConfigOption(name = "Waypoints", desc = "Enable waypoint placing and ESP rendering.")
    @ConfigEditorBoolean
    var enabled = false

    @Expose @JvmField
    @ConfigOption(name = "Show Labels", desc = "Show waypoint name and distance above each box.")
    @ConfigEditorBoolean
    var showLabels = true

    @Expose @JvmField
    @ConfigOption(name = "Place Key", desc = "Keybind to place a waypoint at your current position.")
    @ConfigEditorKeybind(defaultKey = GLFW.GLFW_KEY_UNKNOWN)
    var placeKey = GLFW.GLFW_KEY_UNKNOWN

    @Expose @JvmField
    @ConfigOption(name = "Waypoint Color", desc = "Color of the waypoint ESP boxes.")
    @ConfigEditorColour
    var color = "0:255:255:79:79"

    @Expose @JvmField var colorR = 255
    @Expose @JvmField var colorG = 79
    @Expose @JvmField var colorB = 79
}
