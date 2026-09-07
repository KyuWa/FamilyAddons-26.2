package org.kyowa.familyaddons.features

import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.monster.cubemob.MagmaCube
import org.kyowa.familyaddons.config.FamilyConfigManager

/**
 * Outlines Kuudra himself (the giant magma cube, width >= 14.5) using the
 * vanilla entity-outline pass, which draws through walls and lava.
 *
 * Queried per rendered entity from EntityOutlineMixin, so there is no scan:
 * the size check is O(1) on the entity being extracted. Gated on being in
 * Kuudra's Hollow so a large magma cube elsewhere is never outlined.
 */
object KuudraHighlight {

    private const val KUUDRA_MIN_WIDTH = 14.5f

    private fun cfg() = FamilyConfigManager.config.kuudra

    /** ARGB outline color for [entity], or 0 for "no outline". */
    fun getOutlineColor(entity: Entity): Int {
        val cfg = cfg()
        if (!cfg.kuudraHighlightEnabled) return 0
        if (entity !is MagmaCube || entity.bbWidth < KUUDRA_MIN_WIDTH) return 0
        if (!entity.isAlive) return 0
        if (!AutoRequeue.isInKuudra()) return 0
        if (!cfg.kuudraHighlightBehindWalls) {
            val player = Minecraft.getInstance().player ?: return 0
            if (!player.hasLineOfSight(entity)) return 0
        }
        return parseOutlineColor(cfg.kuudraHighlightColor)
    }

    /** "chroma:alpha:r:g:b" → opaque ARGB. */
    private fun parseOutlineColor(s: String): Int = try {
        val parts = s.split(":")
        (0xFF shl 24) or (parts[2].toInt() shl 16) or (parts[3].toInt() shl 8) or parts[4].toInt()
    } catch (e: Exception) { 0xFFFF5555.toInt() }
}
