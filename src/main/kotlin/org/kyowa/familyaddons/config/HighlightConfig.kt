package org.kyowa.familyaddons.config

import com.google.gson.annotations.Expose
import io.github.notenoughupdates.moulconfig.annotations.ConfigAccordionId
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorAccordion
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorColour
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorDropdown
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorSlider
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorText
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption

/**
 * Highlight/BE — entity ESP + all bestiary features merged into one
 * category. The master toggle below gates everything here (manual name
 * highlight, bestiary highlight/HUD, shulkers, tracers); the bestiary
 * options live in their own accordion.
 */
class HighlightConfig {
    @Expose @JvmField
    @ConfigOption(name = "Enable Highlight", desc = "Master toggle for this whole category — mob name ESP, bestiary highlight/HUD, shulkers and tracers.")
    @ConfigEditorBoolean
    var enabled = true

    @Expose @JvmField
    @ConfigOption(name = "Mob Names", desc = "Comma-separated list of mob names to highlight. Case insensitive.")
    @ConfigEditorText
    var mobNames = ""

    @Expose @JvmField
    @ConfigOption(name = "Color", desc = "Color of the ESP box.")
    @ConfigEditorColour
    var color = "0:255:255:0:0"

    @Expose @JvmField
    @ConfigOption(name = "Drawing Style", desc = "How to draw the highlight.")
    @ConfigEditorDropdown(values = ["AABB", "Outline"])
    var drawingStyle = 0

    @Expose @JvmField
    @ConfigOption(name = "Highlight Shulkers", desc = "Draw a wireframe box on shulker mobs, plus placed shulker boxes and falling-block fakes. Independent of the mob name list above.")
    @ConfigEditorBoolean
    var shulkerHighlightEnabled = false

    @Expose @JvmField
    @ConfigOption(name = "Shulker Color", desc = "Color of the shulker highlight.")
    @ConfigEditorColour
    var shulkerColor = "0:255:200:100:255"

    @Expose @JvmField
    @ConfigOption(name = "Highlight Sparkling Critters", desc = "Highlight sparkling critters even before their nametag shows — detected by name, enchant glint, or the sparkle particles they emit.")
    @ConfigEditorBoolean
    var sparklingHighlightEnabled = false

    @Expose @JvmField
    @ConfigOption(name = "Sparkling Color", desc = "Color of the sparkling critter highlight.")
    @ConfigEditorColour
    var sparklingColor = "0:255:255:230:120"

    @Expose @JvmField
    @ConfigOption(name = "Tracer Lines", desc = "Draw lines from your crosshair to the nearest highlighted mobs (shulkers included).")
    @ConfigEditorBoolean
    var tracerEnabled = false

    @Expose @JvmField
    @ConfigOption(name = "Tracer Count", desc = "How many of the closest highlighted mobs to draw tracers to (1–20).")
    @ConfigEditorSlider(minValue = 1f, maxValue = 20f, minStep = 1f)
    var tracerCount = 5f

    @Expose @JvmField
    @ConfigOption(name = "Tracer Range", desc = "Maximum distance in chunks to draw tracers. Mobs further than this are ignored (ESP is limited to 4 chunks so prob that is best).")
    @ConfigEditorSlider(minValue = 2f, maxValue = 16f, minStep = 1f)
    var tracerChunkRange = 4f

    // ── Bestiary accordion (id=1) ─────────────────────────────
    @Expose @JvmField
    @ConfigOption(name = "Bestiary", desc = "")
    @ConfigEditorAccordion(id = 1)
    var bestiaryAccordion = false

    @Expose @JvmField
    @ConfigAccordionId(id = 1)
    @ConfigOption(name = "Zone Highlight", desc = "Highlight bestiary mobs in the selected zone. Refreshes every 30 seconds.")
    @ConfigEditorBoolean
    var zoneHighlightEnabled = false

    @Expose @JvmField
    @ConfigAccordionId(id = 1)
    @ConfigOption(name = "Bestiary Zone", desc = "Select the zone to highlight bestiary mobs for. Fishing includes all fishing sub-zones (Lava, Backwater Bayou, festivals, Winter).")
    @ConfigEditorDropdown(values = ["None", "Island", "Hub", "The Farming Lands", "The Garden", "Spider's Den", "The End", "Crimson Isle", "Deep Caverns", "Dwarven Mines", "Crystal Hollows", "The Park", "Moonglade Marsh", "Spooky Festival", "The Catacombs", "Fishing", "Mythological Creatures", "Jerry", "Kuudra", "Torrhus Canyon", "Lotus Atoll", "Critter Safari"])
    var bestiaryZone = 0  // 0 = None

    @Expose @JvmField
    @ConfigAccordionId(id = 1)
    @ConfigOption(name = "Hide Maxed Mobs", desc = "On: maxed bestiary mobs are not highlighted. Off: highlight every mob in the zone, maxed or not.")
    @ConfigEditorBoolean
    var hideMaxedMobs = true

    @Expose @JvmField
    @ConfigAccordionId(id = 1)
    @ConfigOption(name = "Bestiary Color", desc = "Color of the bestiary highlight (independent of the ESP color above).")
    @ConfigEditorColour
    var bestiaryColor = "0:255:255:170:0"

    @Expose @JvmField
    @ConfigAccordionId(id = 1)
    @ConfigOption(name = "Bestiary Drawing Style", desc = "How to draw the bestiary highlight.")
    @ConfigEditorDropdown(values = ["AABB", "Outline"])
    var bestiaryDrawingStyle = 0

    @Expose @JvmField
    @ConfigAccordionId(id = 1)
    @ConfigOption(name = "Enable Kill HUD", desc = "Show the Bestiary kill tracker HUD on screen.")
    @ConfigEditorBoolean
    var bestiaryHudEnabled = false

    @Expose @JvmField
    @ConfigAccordionId(id = 1)
    @ConfigOption(name = "HUD Display Mode", desc = "Total: all-time kills for this mob. Session: kills + uptime this session.")
    @ConfigEditorDropdown(values = ["Total", "Session"])
    var displayMode = 0  // 0 = Total, 1 = Session

    @Expose @JvmField
    @ConfigAccordionId(id = 1)
    @ConfigOption(name = "Auto Detect Mob", desc = "Automatically use the first mob in the Bestiary tablist section as the tracked mob. Leave Mob Name blank to use this.")
    @ConfigEditorBoolean
    var autoMobName = false

    @Expose @JvmField
    @ConfigAccordionId(id = 1)
    @ConfigOption(name = "Mob Name", desc = "Manually set the mob to track (e.g. 'Ghost'). Leave blank to use Auto Detect. HUD title will be '[Name] Bestiary'.")
    @ConfigEditorText
    var mobName = ""

    // ── Persisted bestiary state (no GUI options) ─────────────
    @Expose @JvmField
    var savedKills: MutableMap<String, Int> = mutableMapOf()

    @Expose @JvmField
    var maxedMobs: MutableSet<String> = mutableSetOf()

    @Expose var bestiaryHudX: Int = 10
    @Expose var bestiaryHudY: Int = 10
    @Expose var bestiaryHudScale: Float = 1.0f
}
