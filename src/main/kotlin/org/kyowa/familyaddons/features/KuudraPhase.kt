package org.kyowa.familyaddons.features

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import org.kyowa.familyaddons.COLOR_CODE_REGEX

/**
 * Tracks which Kuudra phase the player is currently in. Shared state
 * accessed by multiple features (PearlWaypoints, KuudraCrateWaypoints, ...).
 *
 * Phase 1 = supply collection — bookended by two NPC chat messages from
 * Elle. Outside Phase 1 the supply pickup giants/zombies are gone, so
 * any feature that ESP's them should hide.
 *
 * Register once at mod init via [register].
 */
object KuudraPhase {

    private const val PHASE_1_START = "[NPC] Elle: Okay adventurers, I will go and fish up Kuudra!"
    private const val PHASE_1_END   = "[NPC] Elle: OMG! Great work collecting my supplies!"

    @Volatile private var inP1: Boolean = false

    /** Returns true while we're in the supply-collection phase. */
    fun isInP1(): Boolean = inP1

    fun register() {
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> inP1 = false }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> inP1 = false }

        ClientReceiveMessageEvents.ALLOW_GAME.register { message, _ ->
            val plain = message.string.replace(COLOR_CODE_REGEX, "").trim()
            when (plain) {
                PHASE_1_START -> inP1 = true
                PHASE_1_END   -> inP1 = false
            }
            true
        }
    }
}
