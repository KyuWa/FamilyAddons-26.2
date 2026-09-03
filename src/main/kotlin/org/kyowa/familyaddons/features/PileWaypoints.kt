package org.kyowa.familyaddons.features

import net.minecraft.client.Camera
import net.minecraft.client.renderer.SubmitNodeCollector
import com.mojang.blaze3d.vertex.PoseStack
import org.kyowa.familyaddons.config.FamilyConfigManager
import org.kyowa.familyaddons.features.pearl.Place

/**
 * Beacon beams over the 6 Kuudra supply piles during Phase 1.
 * Hides beams over piles with SUPPLIES RECEIVED stands.
 */
object PileWaypoints {

    private const val BEAM_BASE_Y = 79.0
    private const val BEAM_HEIGHT = 80.0

    fun register() {
        // No event handlers — render is driven by WorldRendererMixin.
    }

    fun hasBeams(): Boolean {
        val cfg = FamilyConfigManager.config.kuudra
        if (!cfg.pileWaypointsEnabled) return false
        if (!AutoRequeue.isInKuudra()) return false
        if (!KuudraPhase.isInP1()) return false
        return true
    }

    fun onWorldRender(matrices: PoseStack, collector: SubmitNodeCollector, camera: Camera) {
        if (!hasBeams()) return
        val cfg = FamilyConfigManager.config.kuudra
        val color = parseColor(cfg.pileWaypointColor, floatArrayOf(0.31f, 1f, 0.31f, 1f))

        val cam = camera.position()
        matrices.pushPose()
        matrices.translate(-cam.x, -cam.y, -cam.z)

        for (place in Place.values()) {
            if (KuudraOccupancy.isOccupied(place)) continue

            BeaconBeamRenderer.drawBeam(
                matrices, collector,
                place.location.x, BEAM_BASE_Y, place.location.z,
                BEAM_HEIGHT,
                color[0], color[1], color[2],
                color[3],
            )
        }

        matrices.popPose()
    }

    /** Parse "chroma:alpha:r:g:b" → FloatArray(r, g, b, alpha) all 0..1. */
    private fun parseColor(s: String, fallback: FloatArray): FloatArray {
        return try {
            val p = s.split(":")
            floatArrayOf(
                p[2].toInt() / 255f,
                p[3].toInt() / 255f,
                p[4].toInt() / 255f,
                p[1].toInt() / 255f,
            )
        } catch (e: Exception) { fallback }
    }
}