package org.kyowa.familyaddons.config

import com.google.gson.annotations.Expose
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorText
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption

class ChatFiltersConfig {
    @Expose @JvmField
    @ConfigOption(name = "Enable Chat Filters", desc = "Hide chat messages containing filtered phrases.")
    @ConfigEditorBoolean
    var enabled = false

    @Expose @JvmField
    @ConfigOption(name = "Filtered Phrases", desc = "Comma-separated list of phrases to hide from chat.")
    @ConfigEditorText
    var chatFilterList = "Your Implosion hit"
}
