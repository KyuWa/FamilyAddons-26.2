package org.kyowa.familyaddons.config

import com.google.gson.annotations.Expose
import io.github.notenoughupdates.moulconfig.annotations.*

class PartyConfig {
    @Expose @JvmField
    @ConfigOption(name = "Rep Check", desc = "Auto check Crimson Isle rep when someone joins party.")
    @ConfigEditorBoolean
    var repCheckEnabled = false

    @Expose @JvmField
    @ConfigOption(name = "Party Calc", desc = "Enable calc/c command in party chat.")
    @ConfigEditorBoolean
    var calcEnabled = true

    // Party Commands accordion
    @Expose @JvmField
    @ConfigOption(name = "Party Commands", desc = "")
    @ConfigEditorAccordion(id = 10)
    var partyCommandsAccordion = false

    @Expose @JvmField
    @ConfigAccordionId(id = 10)
    @ConfigOption(name = "Enable Party Commands", desc = "Master toggle for all party commands below.")
    @ConfigEditorBoolean
    var commandsEnabled = true

    @Expose @JvmField
    @ConfigAccordionId(id = 10)
    @ConfigOption(name = "Whitelist", desc = "Comma-separated IGNs that can use party commands. Leave blank to allow anyone.")
    @ConfigEditorText
    var partyWhitelist = ""

    @Expose @JvmField
    @ConfigAccordionId(id = 10)
    @ConfigOption(name = "!pt", desc = "Allow whitelisted players to transfer party leadership.")
    @ConfigEditorBoolean
    var ptEnabled = true

    @Expose @JvmField
    @ConfigAccordionId(id = 10)
    @ConfigOption(name = "!warp", desc = "Allow whitelisted players to warp the party.")
    @ConfigEditorBoolean
    var warpEnabled = true

    @Expose @JvmField
    @ConfigAccordionId(id = 10)
    @ConfigOption(name = "!k / !kick", desc = "Allow whitelisted players to kick members from party.")
    @ConfigEditorBoolean
    var kickEnabled = true

    @Expose @JvmField
    @ConfigAccordionId(id = 10)
    @ConfigOption(name = "!inv / !invite", desc = "Allow whitelisted players to invite others to party.")
    @ConfigEditorBoolean
    var invEnabled = true

    @Expose @JvmField
    @ConfigAccordionId(id = 10)
    @ConfigOption(name = "!allinv / !ai", desc = "Allow whitelisted players to toggle all invite.")
    @ConfigEditorBoolean
    var allinvEnabled = true
}
