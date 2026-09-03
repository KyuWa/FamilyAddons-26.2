package org.kyowa.familyaddons.config

import com.google.gson.annotations.Expose
import io.github.notenoughupdates.moulconfig.annotations.ConfigAccordionId
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorAccordion
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorKeybind
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorSlider
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption
import org.lwjgl.glfw.GLFW

class UtilitiesConfig {
    @Expose @JvmField
    @ConfigOption(name = "Command Shortcuts", desc = "Enable short command aliases: /museum, /pw, /koff")
    @ConfigEditorBoolean
    var commandShortcuts = true

    @Expose @JvmField
    @ConfigOption(name = "Sign Math", desc = "Evaluate math expressions typed on signs before sending.")
    @ConfigEditorBoolean
    var signMath = true

    @Expose @JvmField
    @ConfigOption(name = "Item Prices", desc = "Show SkyBlock item prices in tooltips (AH, BIN, Bazaar, Pets).")
    @ConfigEditorBoolean
    var itemPrices = false

    @Expose @JvmField
    @ConfigOption(name = "Lock Hotbar Scroll", desc = "Prevent hotbar scroll from wrapping around (slot 1 won't go to slot 9 and vice versa).")
    @ConfigEditorBoolean
    var lockHotbarScroll = false

    @Expose @JvmField
    @ConfigOption(name = "Highlight Rescan Interval", desc = "How often (in ticks) to scan for mobs to highlight. Lower = faster detection, higher = better performance. Default 20 (1 second).")
    @ConfigEditorSlider(minValue = 1f, maxValue = 20f, minStep = 1f)
    var highlightRescanInterval = 20f

    @Expose @JvmField
    @ConfigOption(name = "Arachne Timer", desc = "Show a countdown timer hologram when an Arachne Crystal is placed.")
    @ConfigEditorBoolean
    var arachneTimer = false

    // ── Camera ────────────────────────────────────────────────────────
    @Expose @JvmField
    @ConfigOption(name = "Camera", desc = "")
    @ConfigEditorAccordion(id = 80)
    var cameraAccordion = false

    @Expose @JvmField
    @ConfigAccordionId(id = 80)
    @ConfigOption(name = "Camera Clip", desc = "Allow the third-person camera to clip through blocks instead of zooming in when something is behind you.")
    @ConfigEditorBoolean
    var cameraClip = false

    @Expose @JvmField
    @ConfigAccordionId(id = 80)
    @ConfigOption(name = "No Front Camera", desc = "Skip 3rd-person front view when cycling perspectives — F5 toggles only between 1st and 3rd person.")
    @ConfigEditorBoolean
    var noFrontCamera = false

    @Expose @JvmField
    @ConfigAccordionId(id = 80)
    @ConfigOption(name = "Custom Distance", desc = "Use a custom third-person camera distance instead of vanilla 4.0.")
    @ConfigEditorBoolean
    var cameraDistEnabled = false

    @Expose @JvmField
    @ConfigAccordionId(id = 80)
    @ConfigOption(name = "Distance", desc = "Distance of the third-person camera from the player. Vanilla is 4.0. Has no effect unless Custom Distance is enabled.")
    @ConfigEditorSlider(minValue = 3f, maxValue = 12f, minStep = 0.1f)
    var cameraDist = 4f

    @Expose @JvmField
    @ConfigAccordionId(id = 80)
    @ConfigOption(name = "Freelook", desc = "Spin the camera around your character while your character keeps facing the way they were. Hold (or toggle) the keybind below.")
    @ConfigEditorBoolean
    var freelookEnabled = false

    @Expose @JvmField
    @ConfigAccordionId(id = 80)
    @ConfigOption(name = "Freelook Toggle Mode", desc = "If on, the freelook key toggles freelook on/off. If off (default), freelook is only active while the key is held down.")
    @ConfigEditorBoolean
    var freelookToggleMode = false

    @Expose @JvmField
    @ConfigAccordionId(id = 80)
    @ConfigOption(name = "Freelook Key", desc = "Hold (or press, if Toggle Mode is on) to activate freelook.")
    @ConfigEditorKeybind(defaultKey = GLFW.GLFW_KEY_UNKNOWN)
    var freelookKey = GLFW.GLFW_KEY_UNKNOWN
}