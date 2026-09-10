package org.kyowa.familyaddons.config

import com.google.gson.annotations.Expose
import io.github.notenoughupdates.moulconfig.annotations.ConfigAccordionId
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorAccordion
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorButton
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorColour
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorDropdown
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorText
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption

class NameChangerConfig {

    @Expose @JvmField
    @ConfigOption(name = "Show Custom Names", desc = "Draw other players' approved custom names (chat, tab list, nametags). Off = everyone plain.")
    @ConfigEditorBoolean
    var enabled = true

    @Expose @JvmField
    @ConfigOption(name = "Animate", desc = "Let <wave> and <rainbow> names move. Off = they hold still.")
    @ConfigEditorBoolean
    var animate = true

    // ── Easy builder: no codes needed ──────────────────────────────────
    @Expose @JvmField
    @ConfigOption(name = "Easy Builder", desc = "Pick colours and a style, press Build. No codes needed.")
    @ConfigEditorAccordion(id = 1)
    var builderAccordion = false

    @Expose @JvmField
    @ConfigAccordionId(id = 1)
    @ConfigOption(name = "Text", desc = "What your name should say, max 24 characters. Leave empty to use your IGN.")
    @ConfigEditorText
    var builderText = ""

    @Expose @JvmField
    @ConfigAccordionId(id = 1)
    @ConfigOption(name = "Style", desc = "Solid: one colour. Gradient: blends colour 1 to 2 (to 3) across the letters, still. Wave: the same blend sweeping along the name. Rainbow: moving rainbow, ignores the colours. Colour Cycle: your colours looping along the name.")
    @ConfigEditorDropdown(values = ["Solid Colour", "Gradient (still)", "Wave (moving)", "Rainbow (moving)", "Colour Cycle (moving)"])
    var builderStyle = 2

    @Expose @JvmField
    @ConfigAccordionId(id = 1)
    @ConfigOption(name = "Colour 1", desc = "First colour. Click the box for the colour wheel.")
    @ConfigEditorColour
    var builderColor1 = "0:255:200:110:255"

    @Expose @JvmField
    @ConfigAccordionId(id = 1)
    @ConfigOption(name = "Colour 2", desc = "Second colour (Gradient, Wave, Colour Cycle).")
    @ConfigEditorColour
    var builderColor2 = "0:255:75:20:125"

    @Expose @JvmField
    @ConfigAccordionId(id = 1)
    @ConfigOption(name = "Use Colour 3", desc = "Add a third colour to the blend.")
    @ConfigEditorBoolean
    var builderUseColor3 = false

    @Expose @JvmField
    @ConfigAccordionId(id = 1)
    @ConfigOption(name = "Colour 3", desc = "Third colour, only used when Use Colour 3 is on.")
    @ConfigEditorColour
    var builderColor3 = "0:255:85:255:255"

    @Expose @JvmField
    @ConfigAccordionId(id = 1)
    @ConfigOption(name = "Bold", desc = "")
    @ConfigEditorBoolean
    var builderBold = false

    @Expose @JvmField
    @ConfigAccordionId(id = 1)
    @ConfigOption(name = "Italic", desc = "")
    @ConfigEditorBoolean
    var builderItalic = false

    @Expose @JvmField
    @ConfigAccordionId(id = 1)
    @ConfigOption(name = "Underline", desc = "")
    @ConfigEditorBoolean
    var builderUnderline = false

    @JvmField
    @ConfigAccordionId(id = 1)
    @ConfigOption(name = "Build & Preview", desc = "Turn the choices above into your name, show it in chat, and put it in My Name so Submit can send it.")
    @ConfigEditorButton(buttonText = "Build")
    var builderApply: Runnable = Runnable { org.kyowa.familyaddons.features.NameBuilder.apply() }

    // ── Submit / preview / remove ───────────────────────────────────────
    @JvmField
    @ConfigOption(name = "Submit For Approval", desc = "Send My Name (from the builder or Advanced) to be reviewed. It shows for everyone once approved. One submission per 10 minutes.")
    @ConfigEditorButton(buttonText = "Submit")
    var submit: Runnable = Runnable { org.kyowa.familyaddons.features.NameSync.submit() }

    @JvmField
    @ConfigOption(name = "Preview", desc = "Print My Name in chat as it would look, without submitting.")
    @ConfigEditorButton(buttonText = "Preview")
    var preview: Runnable = Runnable { org.kyowa.familyaddons.features.NameSync.preview() }

    @JvmField
    @ConfigOption(name = "Remove My Name", desc = "Take your custom name down for everyone.")
    @ConfigEditorButton(buttonText = "Remove")
    var remove: Runnable = Runnable { org.kyowa.familyaddons.features.NameSync.remove() }

    // ── Advanced: raw template ──────────────────────────────────────────
    @Expose @JvmField
    @ConfigOption(name = "Advanced", desc = "The raw template behind your name. The builder writes it for you; edit it here if you know the codes.")
    @ConfigEditorAccordion(id = 2)
    var advancedAccordion = false

    @Expose @JvmField
    @ConfigAccordionId(id = 2)
    @ConfigOption(name = "My Name", desc = "How your IGN should show for everyone with the mod, max 24 visible characters. Codes: &a &l &n &o &r, <#ff8800>, <gradient:#a:#b>text</gradient>, <wave:#a:#b>text</wave> (moving band), <rainbow>text</rainbow>; any number of colours per tag (#a:#b:#c...). Run /fa name help for examples.")
    @ConfigEditorText
    var myName = ""

    @Expose @JvmField
    @ConfigOption(name = "Local Nickname", desc = "Plain-text name shown in place of your IGN on YOUR screen only (chat, tab, nametag). No colours, no approval, nobody else sees it. Empty = off.")
    @ConfigEditorText
    var localNick = ""
}
