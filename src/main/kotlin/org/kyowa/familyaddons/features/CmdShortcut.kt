package org.kyowa.familyaddons.features

import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import org.kyowa.familyaddons.config.FamilyConfigManager

object CmdShortcut {

    private data class Shortcut(val name: String, val target: String)

    private val shortcuts = listOf(
        Shortcut("museum", "warp museum"),
        Shortcut("pw",     "p warp"),
        Shortcut("koff",   "p kickoffline"),
    )

    fun register() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            for (sc in shortcuts) {
                dispatcher.register(
                    literal(sc.name).executes { ctx ->
                        if (!FamilyConfigManager.config.utilities.commandShortcuts) return@executes 0
                        ctx.source.player.connection.sendCommand(sc.target)
                        1
                    }
                )
            }
        }
    }
}
