package org.kyowa.familyaddons.commands

// All parkour commands have been moved to /fa parkour subcommands in TestCommand.kt.
// This file is intentionally empty but kept so existing imports don't break.
// You can delete ParkourCommand.register() calls — registration now happens inside TestCommand.

object ParkourCommand {
    fun register() {
        // No-op — parkour commands are registered under /fa parkour in TestCommand
    }
}