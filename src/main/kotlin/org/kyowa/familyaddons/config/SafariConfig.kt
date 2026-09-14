package org.kyowa.familyaddons.config

import com.google.gson.annotations.Expose
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorColour
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption

/**
 * Critter Safari: the run tracker and every Safari highlight.
 *
 * Deliberately separate from Highlight/BE — none of this is gated by that
 * category's master toggle, and the Safari is no longer one of its zones.
 */
class SafariConfig {
    @Expose @JvmField
    @ConfigOption(name = "Enable Critter Safari", desc = "Master toggle for everything in this category. Independent of the Highlight/BE category.")
    @ConfigEditorBoolean
    var enabled = true

    // ── highlights ─────────────────────────────────────────────────────

    @Expose @JvmField
    @ConfigOption(name = "Highlight Critters", desc = "Outline every Safari species, the awkward ones included — Hideyho, Hideonwall, Hideonfloor, Duplico, Bloodbat and the rest of the 37. Matched by the name label Hypixel puts above each critter, so the ones that render as bare vanilla mobs are caught too.")
    @ConfigEditorBoolean
    var critterEsp = false

    @Expose @JvmField
    @ConfigOption(name = "Critter Color", desc = "Colour of the critter outline.")
    @ConfigEditorColour
    var critterEspColor = "0:255:255:170:0"

    @Expose @JvmField
    @ConfigOption(name = "Highlight Sparkling Critters", desc = "Call out sparkling critters, detected by the name label, an enchant glint on their equipment, or the sparkle particles they emit.")
    @ConfigEditorBoolean
    var sparklingEsp = false

    @Expose @JvmField
    @ConfigOption(name = "Sparkling Color", desc = "Colour of the sparkling critter highlight.")
    @ConfigEditorColour
    var sparklingColor = "0:255:255:230:120"

    @Expose @JvmField
    @ConfigOption(name = "Highlight Floor Drops", desc = "Outline the block under a floor drop, the sparkling little item pile on the ground, so you spot it from afar.")
    @ConfigEditorBoolean
    var floorDrops = false

    @Expose @JvmField
    @ConfigOption(name = "Floor Drop Color", desc = "Colour of the floor drop block outline.")
    @ConfigEditorColour
    var floorDropsColor = "0:255:80:255:80"

    // ── run tracker ────────────────────────────────────────────────────

    @Expose @JvmField
    @ConfigOption(name = "Critter Tracker", desc = "Track which species each player has caught this run, per biome. Your own catches are read from CAPTURE!, a partymate's from the LOOT SHARE! line that names them, so the party view only covers catches loot share tells you about.")
    @ConfigEditorBoolean
    var tracker = true

    @Expose @JvmField
    @ConfigOption(name = "Tracker HUD", desc = "Show the per-biome progress panel. Unique species only, so a second Gemzie adds nothing.")
    @ConfigEditorBoolean
    var trackerHud = true

    @Expose @JvmField
    @ConfigOption(name = "Per-player lines", desc = "List each player under the biome totals, showing the biome they are working and how far it is.")
    @ConfigEditorBoolean
    var perPlayerLines = true

    @Expose @JvmField
    @ConfigOption(name = "Count every catch", desc = "Also show each player's total catches including duplicates, next to their unique count. Off shows uniques only.")
    @ConfigEditorBoolean
    var showTotals = true

    @Expose @JvmField
    @ConfigOption(name = "Party commands", desc = "Let the party ask for the run in party chat: !safari or !critters posts the current run, !runs posts your run history. Answers go to party chat, so this posts on your account. Still obeys the Party category's whitelist and its master toggle.")
    @ConfigEditorBoolean
    var partyCommands = false

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

    // Run history, written when a run with catches ends. Not user-editable.
    @Expose @JvmField var runsDone = 0
    @Expose @JvmField var lifetimeCatches = 0
    @Expose @JvmField var bestUnique = 0

    @Expose @JvmField var hudX = 10
    @Expose @JvmField var hudY = 80
    @Expose @JvmField var hudScale = "1.0"
}
