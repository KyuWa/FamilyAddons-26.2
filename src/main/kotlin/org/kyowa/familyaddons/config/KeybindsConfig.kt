package org.kyowa.familyaddons.config

import com.google.gson.annotations.Expose
import io.github.notenoughupdates.moulconfig.annotations.*
import org.lwjgl.glfw.GLFW

class KeybindsConfig {

    @Expose @JvmField
    @ConfigOption(name = "GFS Ender Pearl", desc = "")
    @ConfigEditorAccordion(id = 30)
    var pearlAccordion = false

    @Expose @JvmField
    @ConfigAccordionId(id = 30)
    @ConfigOption(name = "Enable", desc = "Enable the GFS Ender Pearl keybind.")
    @ConfigEditorBoolean
    var pearlEnabled = true

    @Expose @JvmField
    @ConfigAccordionId(id = 30)
    @ConfigOption(name = "Keybind", desc = "Press to /gfs ENDER_PEARL up to 16.")
    @ConfigEditorKeybind(defaultKey = GLFW.GLFW_KEY_UNKNOWN)
    var pearlKey = GLFW.GLFW_KEY_UNKNOWN

    @Expose @JvmField
    @ConfigOption(name = "GFS Superboom TNT", desc = "")
    @ConfigEditorAccordion(id = 31)
    var superboomAccordion = false

    @Expose @JvmField
    @ConfigAccordionId(id = 31)
    @ConfigOption(name = "Enable", desc = "Enable the GFS Superboom TNT keybind.")
    @ConfigEditorBoolean
    var superboomEnabled = true

    @Expose @JvmField
    @ConfigAccordionId(id = 31)
    @ConfigOption(name = "Keybind", desc = "Press to /gfs SUPERBOOM_TNT up to 64.")
    @ConfigEditorKeybind(defaultKey = GLFW.GLFW_KEY_UNKNOWN)
    var superboomKey = GLFW.GLFW_KEY_UNKNOWN

    @Expose @JvmField
    @ConfigOption(name = "GFS Inflatable Jerry", desc = "")
    @ConfigEditorAccordion(id = 32)
    var jerryAccordion = false

    @Expose @JvmField
    @ConfigAccordionId(id = 32)
    @ConfigOption(name = "Enable", desc = "Enable the GFS Inflatable Jerry keybind.")
    @ConfigEditorBoolean
    var jerryEnabled = true

    @Expose @JvmField
    @ConfigAccordionId(id = 32)
    @ConfigOption(name = "Keybind", desc = "Press to /gfs INFLATABLE_JERRY up to 64.")
    @ConfigEditorKeybind(defaultKey = GLFW.GLFW_KEY_UNKNOWN)
    var jerryKey = GLFW.GLFW_KEY_UNKNOWN

    @Expose @JvmField
    @ConfigOption(name = "GFS Decoy", desc = "")
    @ConfigEditorAccordion(id = 33)
    var decoyAccordion = false

    @Expose @JvmField
    @ConfigAccordionId(id = 33)
    @ConfigOption(name = "Enable", desc = "Enable the GFS Decoy keybind.")
    @ConfigEditorBoolean
    var decoyEnabled = true

    @Expose @JvmField
    @ConfigAccordionId(id = 33)
    @ConfigOption(name = "Keybind", desc = "Press to /gfs DECOY up to 64.")
    @ConfigEditorKeybind(defaultKey = GLFW.GLFW_KEY_UNKNOWN)
    var decoyKey = GLFW.GLFW_KEY_UNKNOWN

    @Expose @JvmField
    @ConfigOption(name = "GFS Toxic Arrow Poison", desc = "")
    @ConfigEditorAccordion(id = 34)
    var tapAccordion = false

    @Expose @JvmField
    @ConfigAccordionId(id = 34)
    @ConfigOption(name = "Enable", desc = "Enable the GFS Toxic Arrow Poison keybind.")
    @ConfigEditorBoolean
    var tapEnabled = true

    @Expose @JvmField
    @ConfigAccordionId(id = 34)
    @ConfigOption(name = "Keybind", desc = "Press to /gfs TOXIC_ARROW_POISON up to 192 (3 stacks).")
    @ConfigEditorKeybind(defaultKey = GLFW.GLFW_KEY_UNKNOWN)
    var tapKey = GLFW.GLFW_KEY_UNKNOWN
}
