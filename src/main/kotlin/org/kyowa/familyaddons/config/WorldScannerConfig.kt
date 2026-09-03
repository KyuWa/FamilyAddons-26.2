package org.kyowa.familyaddons.config

import com.google.gson.annotations.Expose
import io.github.notenoughupdates.moulconfig.annotations.*

class WorldScannerConfig {

    @Expose @JvmField
    @ConfigOption(name = "Enable World Scanner", desc = "Scan chunks for Crystal Hollows structures and show waypoints.")
    @ConfigEditorBoolean
    var enabled = false

    @Expose @JvmField
    @ConfigOption(name = "Scan Crystals", desc = "Scan for crystal waypoints (King, Queen, Divan, City, Temple, Bal).")
    @ConfigEditorBoolean
    var scanCrystals = false

    @Expose @JvmField
    @ConfigOption(name = "Scan Mob Spots", desc = "Scan for mob spawn locations (Corleone, Key Guardian, Xalx, Pete, Odawa).")
    @ConfigEditorBoolean
    var scanMobSpots = false

    @Expose @JvmField
    @ConfigOption(name = "Scan Fairy Grottos", desc = "Scan for Fairy Grottos.")
    @ConfigEditorBoolean
    var scanFairyGrottos = false

    @Expose @JvmField
    @ConfigOption(name = "Scan Dragon Nest", desc = "Scan for the Golden Dragon nest.")
    @ConfigEditorBoolean
    var scanDragonNest = false

    @Expose @JvmField
    @ConfigOption(name = "Scan Worm Fishing", desc = "Scan for worm fishing lava spots.")
    @ConfigEditorBoolean
    var scanWormFishing = false

    @Expose @JvmField
    @ConfigOption(name = "Lava ESP", desc = "Highlight exposed lava blocks.")
    @ConfigEditorBoolean
    var lavaEsp = false

    @Expose @JvmField
    @ConfigOption(name = "Water ESP", desc = "Highlight exposed water blocks.")
    @ConfigEditorBoolean
    var waterEsp = false

    @Expose @JvmField
    @ConfigOption(name = "ESP Range", desc = "Range in blocks for lava and water ESP.")
    @ConfigEditorSlider(minValue = 8f, maxValue = 128f, minStep = 1f)
    var espRange = 32f

    @Expose @JvmField
    @ConfigOption(name = "Render Style", desc = "Outline: wireframe box. Filled: solid box.")
    @ConfigEditorDropdown(values = ["Outline", "Filled"])
    var renderStyle = 0  // 0 = Outline, 1 = Filled

    @Expose @JvmField
    @ConfigOption(name = "Render Component", desc = "Show name and distance labels above waypoints.")
    @ConfigEditorBoolean
    var renderText = true

    @Expose @JvmField
    @ConfigOption(name = "Send Coords in Chat", desc = "Send coordinates to chat when a structure is found.")
    @ConfigEditorBoolean
    var sendCoordsInChat = false
}
