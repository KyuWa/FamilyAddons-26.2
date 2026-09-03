package org.kyowa.familyaddons.config

import com.google.gson.annotations.Expose
import io.github.notenoughupdates.moulconfig.annotations.*

class CrimsonIsleConfig {

    // ── Mini Boss Timer accordion (id = 1) ────────────────────
    @Expose @JvmField
    @ConfigOption(name = "Mini Boss Timer", desc = "")
    @ConfigEditorAccordion(id = 1)
    var miniBossAccordion = false

    @Expose @JvmField
    @ConfigAccordionId(id = 1)
    @ConfigOption(
        name = "Enable",
        desc = "Show a 2-minute countdown per Crimson Isle mini-boss when you see their DOWN message in chat."
    )
    @ConfigEditorBoolean
    var miniBossTimer = false

    @Expose @JvmField var miniBossHudX = -1
    @Expose @JvmField var miniBossHudY = -1

    @Expose @JvmField
    @ConfigAccordionId(id = 1)
    @ConfigOption(name = "HUD Scale", desc = "Size of the Mini Boss Timer list on screen.")
    @ConfigEditorText
    var miniBossHudScale = "1"
}