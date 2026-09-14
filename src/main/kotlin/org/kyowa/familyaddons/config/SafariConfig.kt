package org.kyowa.familyaddons.config

import com.google.gson.annotations.Expose
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption

/** Critter Safari: who has caught what, per biome, this run. */
class SafariConfig {
    @Expose @JvmField
    @ConfigOption(name = "Critter Tracker", desc = "Track which species each player has caught this run, per biome. Your own catches are read from CAPTURE!, a partymate's from the LOOT SHARE! line that names them, so the party view only covers catches loot share tells you about.")
    @ConfigEditorBoolean
    var enabled = true

    @Expose @JvmField
    @ConfigOption(name = "Tracker HUD", desc = "Show the per-biome progress panel. Unique species only, so a second Gemzie adds nothing.")
    @ConfigEditorBoolean
    var trackerHud = true

    @Expose @JvmField
    @ConfigOption(name = "Per-player lines", desc = "List each player under the biome totals, with how many of each biome they have caught.")
    @ConfigEditorBoolean
    var perPlayerLines = true

    @Expose @JvmField
    @ConfigOption(name = "Only in the Safari", desc = "Hide the panel outside the Critter Safari. Off shows it anywhere, which is useful for checking a finished run.")
    @ConfigEditorBoolean
    var onlyInSafari = true

    @Expose @JvmField
    @ConfigOption(name = "Announce player finished a biome", desc = "Say in your own chat when someone has caught every species in a biome.")
    @ConfigEditorBoolean
    var announcePlayerBiome = true

    @Expose @JvmField
    @ConfigOption(name = "Announce biome done", desc = "Say in your own chat when the party between them has cleared a biome.")
    @ConfigEditorBoolean
    var announcePartyBiome = true

    @Expose @JvmField
    @ConfigOption(name = "Also post to party chat", desc = "Send those announcements to party chat as well. Off by default: this posts on your account, and everyone running the mod would otherwise say the same thing at once.")
    @ConfigEditorBoolean
    var announceToParty = false

    @Expose @JvmField var hudX = 10
    @Expose @JvmField var hudY = 80
    @Expose @JvmField var hudScale = "1.0"
}
