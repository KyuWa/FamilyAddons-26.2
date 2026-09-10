package org.kyowa.familyaddons.config

import com.google.gson.annotations.Expose
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorColour
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption

/** Foraging: the Big Helix tree route on Torrhus Canyon. */
class ForagingConfig {
    @Expose @JvmField
    @ConfigOption(name = "Helix Tree Waypoints", desc = "Big Helix tree route on Torrhus Canyon: box outlines through walls (trees green, etherwarp spots cyan, Evasive shop white) with a distance label. Only the current stop is drawn.")
    @ConfigEditorBoolean
    var helixWaypoints = false

    @Expose @JvmField
    @ConfigOption(name = "Helix Tracer", desc = "Tracer line to the next stop of the Helix route; it auto-advances when you reach the stop. /fa helix next|prev|reset|list steps it by hand.")
    @ConfigEditorBoolean
    var helixTracer = true

    @Expose @JvmField
    @ConfigOption(name = "Helix Tracer Color", desc = "Colour of the tracer line to the next Helix stop.")
    @ConfigEditorColour
    var helixTracerColor = "0:230:255:170:0"
}
