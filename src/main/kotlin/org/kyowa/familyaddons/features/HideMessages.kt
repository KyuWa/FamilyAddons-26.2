package org.kyowa.familyaddons.features

import org.kyowa.familyaddons.COLOR_CODE_REGEX
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import org.kyowa.familyaddons.config.FamilyConfigManager

object HideMessages {
    fun register() {
        ClientReceiveMessageEvents.ALLOW_GAME.register { message, _ ->
            if (!FamilyConfigManager.config.chatFilters.enabled) return@register true
            val raw = message.string.replace(COLOR_CODE_REGEX, "")
            val phrases = FamilyConfigManager.config.chatFilters.chatFilterList
                .split(",").map { it.trim() }.filter { it.isNotEmpty() }
            phrases.none { phrase -> raw.contains(phrase, ignoreCase = true) }
        }
    }
}