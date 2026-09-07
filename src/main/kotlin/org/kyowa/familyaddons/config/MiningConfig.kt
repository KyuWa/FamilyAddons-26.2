package org.kyowa.familyaddons.config

import com.google.gson.annotations.Expose
import io.github.notenoughupdates.moulconfig.annotations.*

class MiningConfig {

    // ── Mineshaft ──────────────────────────────────────────────
    @Expose @JvmField
    @ConfigOption(name = "Mineshaft", desc = "")
    @ConfigEditorAccordion(id = 20)
    var mineshaftAccordion = false

    @Expose @JvmField
    @ConfigAccordionId(id = 20)
    @ConfigOption(name = "Corpse ESP", desc = "Show ESP for unclaimed corpses in the mineshaft.")
    @ConfigEditorBoolean
    var corpseESP = true

    @Expose @JvmField
    @ConfigAccordionId(id = 20)
    @ConfigOption(name = "Corpse Drawing Style", desc = "ESP Box draws a wireframe box. Outline draws around the entity model.")
    @ConfigEditorDropdown(values = ["ESP Box", "Outline"])
    var corpseDrawingStyle = 0

    @Expose @JvmField
    @ConfigAccordionId(id = 20)
    @ConfigOption(name = "Corpse Announce", desc = "Announce corpse coords to party chat when you loot a corpse.")
    @ConfigEditorBoolean
    var corpseAnnounce = true

    // ── Pickaxe Ability ────────────────────────────────────────
    @Expose @JvmField
    @ConfigOption(name = "Pickaxe Ability", desc = "")
    @ConfigEditorAccordion(id = 21)
    var pickaxeAccordion = false

    @Expose @JvmField
    @ConfigAccordionId(id = 21)
    @ConfigOption(name = "Pickobulus Timer", desc = "Show a countdown timer on screen when Pickobulus is used.")
    @ConfigEditorBoolean
    var pickobulusTimer = false

    @Expose @JvmField var pickobulusHudX = -1
    @Expose @JvmField var pickobulusHudY = -1
    @Expose @JvmField var pickobulusHudScale = "1.5"
}