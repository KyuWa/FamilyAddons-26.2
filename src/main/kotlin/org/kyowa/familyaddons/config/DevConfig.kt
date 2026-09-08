package org.kyowa.familyaddons.config

import com.google.gson.annotations.Expose
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorColour
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorKeybind
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorText
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption
import org.lwjgl.glfw.GLFW

class DevConfig {

    @Expose @JvmField
    @ConfigOption(name = "Debug Messages", desc = "Owner only: print pearl timing / direction diagnostics to chat and the log. Off = silent.")
    @ConfigEditorBoolean
    var debugMessages = false

    @Expose @JvmField
    @ConfigOption(name = "Discord Tickets", desc = "Owner only: listen on 127.0.0.1:25570 for carry tickets from the local Discord bot (bot.py) and show them in chat with a Claim button.")
    @ConfigEditorBoolean
    var discordTickets = true

    @Expose @JvmField
    @ConfigOption(name = "Ticket Sound", desc = "Ping when a new ticket comes in.")
    @ConfigEditorBoolean
    var discordTicketSound = true

    @Expose @JvmField
    @ConfigOption(name = "Ticket Title", desc = "Also flash the ticket on screen using the DT title (needs DT Title enabled in Kuudra).")
    @ConfigEditorBoolean
    var discordTicketTitle = false

    @Expose @JvmField
    @ConfigOption(name = "Discord Debug Port", desc = "For Vesktop: start it with --remote-debugging-port=9229 and put 9229 here so View Ticket can jump inside the running app. 0 = off.")
    @ConfigEditorText
    var discordDebugPort = "0"

    @Expose @JvmField
    @ConfigOption(name = "Helix Tree Waypoints", desc = "Owner only: Big Helix tree route on Torrhus Canyon (trees green, etherwarp spots cyan, Evasive shop white).")
    @ConfigEditorBoolean
    var helixWaypoints = true

    @Expose @JvmField
    @ConfigOption(name = "Helix Tracer", desc = "Tracer line to the next stop of the Helix route; auto-advances when you reach it (/fa helix next|prev|reset|list).")
    @ConfigEditorBoolean
    var helixTracer = true

    @Expose @JvmField
    @ConfigOption(name = "Helix Tracer Color", desc = "Colour of the tracer line to the next Helix stop.")
    @ConfigEditorColour
    var helixTracerColor = "0:230:255:170:0"

    @Expose @JvmField
    @ConfigOption(name = "Name Style", desc = "Draw KyoWaa in the FamilyAddons purple gradient in chat, tab and nametag on this screen. Everyone else's client always does.")
    @ConfigEditorBoolean
    var nameStyle = true

    @Expose @JvmField
    @ConfigOption(name = "Name Changer", desc = "Owner only, your screen only: what your IGN is shown as in chat, tab and nametag. & colour codes work (e.g. &d&lKyo). Empty = off.")
    @ConfigEditorText
    var nameChanger = ""

    @Expose @JvmField
    @ConfigOption(name = "Grab Scoreboard", desc = "Press to print all sidebar scoreboard entries to chat.")
    @ConfigEditorKeybind(defaultKey = GLFW.GLFW_KEY_UNKNOWN)
    var scoreboardKey = GLFW.GLFW_KEY_UNKNOWN

    @Expose @JvmField
    @ConfigOption(name = "Grab Tab List", desc = "Press to print all tab list entries to chat.")
    @ConfigEditorKeybind(defaultKey = GLFW.GLFW_KEY_UNKNOWN)
    var tabListKey = GLFW.GLFW_KEY_UNKNOWN

    @Expose @JvmField
    @ConfigOption(name = "Grab Item NBT", desc = "Press while holding an item to print its full NBT to chat.")
    @ConfigEditorKeybind(defaultKey = GLFW.GLFW_KEY_UNKNOWN)
    var itemNbtKey = GLFW.GLFW_KEY_UNKNOWN

    @Expose @JvmField
    @ConfigOption(name = "Copy Raw Chat", desc = "Press while hovering over a chat message to copy its raw text.")
    @ConfigEditorKeybind(defaultKey = GLFW.GLFW_KEY_UNKNOWN)
    var copyRawChatKey = GLFW.GLFW_KEY_UNKNOWN
}
