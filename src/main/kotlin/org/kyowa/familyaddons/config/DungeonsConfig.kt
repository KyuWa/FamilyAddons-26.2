package org.kyowa.familyaddons.config

import com.google.gson.annotations.Expose
import io.github.notenoughupdates.moulconfig.annotations.ConfigAccordionId
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorAccordion
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorColour
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorSlider
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption

class DungeonsConfig {
    @Expose @JvmField
    @ConfigOption(name = "Auto Requeue", desc = "Auto requeue after each dungeon run.")
    @ConfigEditorBoolean
    var autoRequeue = false

    @Expose @JvmField
    @ConfigOption(name = "DT Title", desc = "Show a fading centered title when someone requests DT in dungeon party chat.")
    @ConfigEditorBoolean
    var dtTitle = false

    @Expose @JvmField
    @ConfigOption(name = "Check Party Size", desc = "Cancel requeue if party has less than 5 players.")
    @ConfigEditorBoolean
    var checkPartySize = false

    @Expose @JvmField
    @ConfigOption(name = "Requeue Delay", desc = "Seconds to wait before requeuing. Default 0.")
    @ConfigEditorSlider(minValue = 0f, maxValue = 10f, minStep = 1f)
    var requeueDelaySecs = 0f

    // -1 = auto-center
    @Expose @JvmField var dungeonDtTitleHudX = -1
    @Expose @JvmField var dungeonDtTitleHudY = -1
    @Expose @JvmField var dungeonDtTitleScale = "2.0"

    // ── Dungeon Highlight accordion (id=1) ────────────────────
    @Expose @JvmField
    @ConfigOption(name = "Dungeon Highlight", desc = "")
    @ConfigEditorAccordion(id = 1)
    var dungeonHighlightAccordion = false

    @Expose @JvmField
    @ConfigAccordionId(id = 1)
    @ConfigOption(name = "Enable Dungeon Highlight", desc = "Outline starred mobs, withers and bats in dungeons. Independent of the Highlight category.")
    @ConfigEditorBoolean
    var dungeonHighlightEnabled = false

    @Expose @JvmField
    @ConfigAccordionId(id = 1)
    @ConfigOption(name = "Behind Walls", desc = "Show the outlines through walls. Off = only mobs you have line of sight to are outlined.")
    @ConfigEditorBoolean
    var dungeonHighlightBehindWalls = true

    @Expose @JvmField
    @ConfigAccordionId(id = 1)
    @ConfigOption(name = "Highlight Starred Mobs", desc = "Highlights starred (✯) dungeon mobs, including Shadow Assassins.")
    @ConfigEditorBoolean
    var dungeonHighlightStar = true

    @Expose @JvmField
    @ConfigAccordionId(id = 1)
    @ConfigOption(name = "Highlight Color", desc = "Outline color for starred mobs.")
    @ConfigEditorColour
    var dungeonHighlightColor = "0:255:255:255:255"

    @Expose @JvmField
    @ConfigAccordionId(id = 1)
    @ConfigOption(name = "Highlight Withers", desc = "Highlights Necron, Goldor, Storm and Maxor.")
    @ConfigEditorBoolean
    var dungeonHighlightWithers = true

    @Expose @JvmField
    @ConfigAccordionId(id = 1)
    @ConfigOption(name = "Wither Color", desc = "Outline color for the wither bosses.")
    @ConfigEditorColour
    var dungeonWitherColor = "0:255:255:0:0"

    @Expose @JvmField
    @ConfigAccordionId(id = 1)
    @ConfigOption(name = "Highlight Bats", desc = "Highlights bats in dungeons (spirit sceptre bats are ignored).")
    @ConfigEditorBoolean
    var dungeonHighlightBats = true

    @Expose @JvmField
    @ConfigAccordionId(id = 1)
    @ConfigOption(name = "Bat Color", desc = "Outline color for dungeon bats.")
    @ConfigEditorColour
    var dungeonBatColor = "0:255:0:255:255"
}