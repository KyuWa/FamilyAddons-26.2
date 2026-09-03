package org.kyowa.familyaddons.features

import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.SubmitNodeCollector
import com.mojang.blaze3d.vertex.PoseStack
import kotlin.math.cos
import kotlin.math.sin

/**
 * Beacon-beam-style column renderer for Kuudra pile/supply waypoints.
 *
 * There is no public textured beacon-beam RenderType, so this draws a solid
 * translucent colored column as custom geometry submitted through the frame's
 * [SubmitNodeCollector] using a position+color quad layer
 * ([FamilyRenderTypes.BEAM]). Visually it's an untextured beacon beam: a bright
 * inner column plus a wider faded outer shell, the inner column slowly rotating.
 */
object BeaconBeamRenderer {

    fun drawBeam(
        matrices: PoseStack,
        collector: SubmitNodeCollector,
        x: Double,
        baseY: Double,
        z: Double,
        height: Double,
        r: Float, g: Float, b: Float,
        alpha: Float = 1f,
    ) {
        val mc = Minecraft.getInstance()

        val time = (mc.level?.gameTime ?: 0L).toFloat() +
            mc.deltaTracker.getGameTimeDeltaPartialTick(false)
        // Slow rotation of the inner column (sign matches the original CT helper).
        val d2 = (time * 0.025 * -1.5).toFloat()

        // Inner-column corners, radius 0.2 around centre, each 90° apart.
        val d4  = 0.5f + cos(d2 + 2.356194490192345f) * 0.2f   // 135°
        val d5  = 0.5f + sin(d2 + 2.356194490192345f) * 0.2f
        val d6  = 0.5f + cos(d2 + (Math.PI.toFloat() / 4f)) * 0.2f   // 45°
        val d7  = 0.5f + sin(d2 + (Math.PI.toFloat() / 4f)) * 0.2f
        val d8  = 0.5f + cos(d2 + 3.9269908169872414f) * 0.2f   // 225°
        val d9  = 0.5f + sin(d2 + 3.9269908169872414f) * 0.2f
        val d10 = 0.5f + cos(d2 + 5.497787143782138f) * 0.2f   // 315°
        val d11 = 0.5f + sin(d2 + 5.497787143782138f) * 0.2f

        matrices.pushPose()
        // Subtract 0.5 so integer world coords centre the beam on the 4-block
        // intersection (corner offsets ≈ 0.5 put the centre back at x,z).
        matrices.translate(x - 0.5, baseY, z - 0.5)

        val topY = height.toFloat()
        val botY = 0f

        collector.submitCustomGeometry(matrices, FamilyRenderTypes.BEAM) { pose, buf ->
            // One vertical quad spanning two corners, bottom solid → top faded to topA.
            fun quad(ax: Float, az: Float, bx: Float, bz: Float, topA: Float, botA: Float) {
                buf.addVertex(pose, ax, topY, az).setColor(r, g, b, topA)
                buf.addVertex(pose, ax, botY, az).setColor(r, g, b, botA)
                buf.addVertex(pose, bx, botY, bz).setColor(r, g, b, botA)
                buf.addVertex(pose, bx, topY, bz).setColor(r, g, b, topA)
            }

            // Inner column — solid bottom, alpha-faded top.
            quad(d4, d5, d6, d7, alpha, 1f)
            quad(d10, d11, d8, d9, alpha, 1f)
            quad(d6, d7, d10, d11, alpha, 1f)
            quad(d8, d9, d4, d5, alpha, 1f)

            // Outer shell — fixed wider column, faded.
            val oa = 0.25f * alpha
            quad(0.2f, 0.2f, 0.8f, 0.2f, oa, 0.25f)
            quad(0.8f, 0.2f, 0.8f, 0.8f, oa, 0.25f)
            quad(0.8f, 0.8f, 0.2f, 0.8f, oa, 0.25f)
            quad(0.2f, 0.8f, 0.2f, 0.2f, oa, 0.25f)
        }

        matrices.popPose()
    }
}
