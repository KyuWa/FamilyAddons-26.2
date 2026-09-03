package org.kyowa.familyaddons.config

import com.google.gson.annotations.Expose
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorButton
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorText
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption
import org.kyowa.familyaddons.commands.TestCommand
import java.awt.Desktop
import java.net.URI

class GeneralConfig {
    @JvmField
    @ConfigOption(name = "HUD Editor", desc = "Open the HUD editor to move and resize HUD elements.")
    @ConfigEditorButton(buttonText = "Open")
    var openHudEditor: Runnable = Runnable {
        TestCommand.openGuiNextTick = true
    }

    @Expose @JvmField
    @ConfigOption(name = "Hypixel API Key", desc = "Your Hypixel API key for rep check. Get one at developer.hypixel.net")
    @ConfigEditorText
    var hypixelApiKey = ""

    @JvmField
    @ConfigOption(name = "Get API Key", desc = "Opens developer.hypixel.net/dashboard in your browser.")
    @ConfigEditorButton(buttonText = "Open")
    var getApiKey: Runnable = Runnable {
        Desktop.getDesktop().browse(URI("https://developer.hypixel.net/dashboard"))
    }

    @Expose @JvmField
    @ConfigOption(name = "Auto Updater", desc = "Check for updates on launch and prompt to download them. Disable to skip update checks entirely.")
    @ConfigEditorBoolean
    var autoUpdaterEnabled = true
}