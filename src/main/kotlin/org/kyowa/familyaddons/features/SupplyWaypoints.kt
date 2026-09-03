package org.kyowa.familyaddons.features

import net.minecraft.client.Camera
import net.minecraft.client.renderer.SubmitNodeCollector
import com.mojang.blaze3d.vertex.PoseStack
import org.kyowa.familyaddons.config.FamilyConfigManager

/**
 * Beacon beams over each detected supply crate during Kuudra Phase 1.
 * Crate positions come from KuudraGiants.getCratePositions, so beams track
 * giants in real time.
 */
object SupplyWaypoints {

    private const val BEAM_HEIGHT = 80.0

    fun register() {
        // No event handlers — render is driven by WorldRendererMixin.
    }

    fun hasBeams(): Boolean {
        val cfg = FamilyConfigManager.config.kuudra
        if (!cfg.supplyWaypointsEnabled) return false
        if (!AutoRequeue.isInKuudra()) return false
        if (!KuudraPhase.isInP1()) return false
        return KuudraGiants.giants.isNotEmpty()
    }

    fun onWorldRender(matrices: PoseStack, collector: SubmitNodeCollector, camera: Camera) {
        if (!hasBeams()) return
        val cfg = FamilyConfigManager.config.kuudra
        val color = parseColor(cfg.supplyWaypointColor, floatArrayOf(1f, 0.78f, 0.31f, 1f))

        val crates = KuudraGiants.getCratePositions()
        if (crates.isEmpty()) return

        val cam = camera.position()
        matrices.pushPose()
        matrices.translate(-cam.x, -cam.y, -cam.z)

        for (crate in crates) {
            BeaconBeamRenderer.drawBeam(
                matrices, collector,
                crate.x, crate.y, crate.z,
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