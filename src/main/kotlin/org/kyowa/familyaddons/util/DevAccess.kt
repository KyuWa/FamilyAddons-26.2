package org.kyowa.familyaddons.util

import net.minecraft.client.Minecraft
import java.util.UUID

/**
 * Gate for developer-only commands (state dumps, capture modes).
 * Matches the logged-in account's UUID, so a renamed account still passes
 * and a name-alike account does not.
 */
object DevAccess {

    private val DEV_UUIDS: Set<UUID> = setOf(
        UUID.fromString("305bcf8c-a93d-4d52-9e8c-b925e8d25682"), // KyoWaa
    )

    fun isDev(): Boolean {
        val id = Minecraft.getInstance().user?.profileId ?: return false
        return id in DEV_UUIDS
    }
}
