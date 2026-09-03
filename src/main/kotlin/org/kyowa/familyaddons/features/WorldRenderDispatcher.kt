package org.kyowa.familyaddons.features

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft

/**
 * 26.2 replacement for the old LevelRenderer mixin: world rendering now goes
 * through the deferred submit-node pipeline, and Fabric's COLLECT_SUBMITS
 * event hands us the frame's [net.minecraft.client.renderer.SubmitNodeCollector]
 * plus a camera-relative PoseStack — the same state the old
 * `renderLevel` tail injection provided via the immediate BufferSource.
 */
object WorldRenderDispatcher {

    fun register() {
        LevelRenderEvents.COLLECT_SUBMITS.register { ctx ->
            if (!Waypoints.hasWaypoints() &&
                !CorpseESP.hasCachedCorpses() &&
                !NpcLocations.hasActiveWaypoints() &&
                !Parkour.hasRings() &&
                !EntityHighlight.hasHighlighted() &&
                !WorldScanner.hasWaypoints() &&
                !KuudraCrateWaypoints.hasCrates() &&
                !KuudraStunWaypoint.hasWaypoint() &&
                !ShulkerBoxHighlight.hasBoxes() &&
                !SparklingCritterHighlight.hasTargets() &&
                !PearlWaypoints.hasWaypoints() &&
                !PileWaypoints.hasBeams() &&
                !SupplyWaypoints.hasBeams()
            ) return@register

            val client = Minecraft.getInstance()
            val collector = ctx.submitNodeCollector()
            val camera = client.gameRenderer.mainCamera()
            val cam = camera.position()
            val matrices = ctx.poseStack()

            matrices.pushPose()

            Waypoints.onWorldRender(matrices, collector, cam)
            CorpseESP.onWorldRender(matrices, collector, cam)
            NpcLocations.onWorldRender(matrices, collector, cam)
            Parkour.onWorldRender(matrices, collector, cam)
            EntityHighlight.onWorldRender(matrices, collector, cam)
            WorldScanner.onWorldRender(matrices, collector, cam)
            KuudraCrateWaypoints.onWorldRender(matrices, collector, camera)
            KuudraStunWaypoint.onWorldRender(matrices, collector, camera)
            ShulkerBoxHighlight.onWorldRender(matrices, collector, camera)
            SparklingCritterHighlight.onWorldRender(matrices, collector, camera)
            PearlWaypoints.onWorldRender(matrices, collector, camera)
            PileWaypoints.onWorldRender(matrices, collector, camera)
            SupplyWaypoints.onWorldRender(matrices, collector, camera)

            matrices.popPose()
        }
    }
}
