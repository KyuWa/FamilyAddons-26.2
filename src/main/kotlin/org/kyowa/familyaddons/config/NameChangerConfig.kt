package org.kyowa.familyaddons.config

import com.google.gson.annotations.Expose
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorButton
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

    @Expose @JvmField
    @ConfigOption(name = "My Name", desc = "How your IGN should show for everyone with the mod, max 24 visible characters. Codes: &a &l &n &o &r, <#ff8800>, <gradient:#a:#b>text</gradient>, <wave:#a:#b>text</wave> (moving band), <rainbow>text</rainbow>; any number of colours per tag (#a:#b:#c...). Run /fa name help for examples.")
    @ConfigEditorText
    var myName = ""

    @JvmField
    @ConfigOption(name = "Submit For Approval", desc = "Send My Name to be reviewed. It shows for everyone once approved. One submission per 10 minutes.")
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

    @Expose @JvmField
    @ConfigOption(name = "Local Nickname", desc = "Plain-text name shown in place of your IGN on YOUR screen only (chat, tab, nametag). No colours, no approval, nobody else sees it. Empty = off.")
    @ConfigEditorText
    var localNick = ""
}
