package org.kyowa.familyaddons.features

import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.SubmitNodeCollector
import com.mojang.blaze3d.vertex.PoseStack
import kotlin.math.cos
import kotlin.math.sin

/**
 * Beacon-beam-style column renderer for Kuudra pile/supply/fuel waypoints.
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
        width: Float = 1f,
    ) {
        // [alpha] is the overall opacity (applied to the whole column, the top
        // fades further); [width] scales the column radius (1 = classic beacon).
        val inner = 0.2f * width.coerceIn(0.1f, 5f)
        val outer = 0.3f * width.coerceIn(0.1f, 5f)
        val mc = Minecraft.getInstance()

        val time = (mc.level?.gameTime ?: 0L).toFloat() +
            mc.deltaTracker.getGameTimeDeltaPartialTick(false)
        // Slow rotation of the inner column (sign matches the original CT helper).
        val d2 = (time * 0.025 * -1.5).toFloat()

        // Inner-column corners around centre, each 90° apart.
        val d4  = 0.5f + cos(d2 + 2.356194490192345f) * inner   // 135°
        val d5  = 0.5f + sin(d2 + 2.356194490192345f) * inner
        val d6  = 0.5f + cos(d2 + (Math.PI.toFloat() / 4f)) * inner   // 45°
        val d7  = 0.5f + sin(d2 + (Math.PI.toFloat() / 4f)) * inner
        val d8  = 0.5f + cos(d2 + 3.9269908169872414f) * inner   // 225°
        val d9  = 0.5f + sin(d2 + 3.9269908169872414f) * inner
        val d10 = 0.5f + cos(d2 + 5.497787143782138f) * inner   // 315°
        val d11 = 0.5f + sin(d2 + 5.497787143782138f) * inner

        matrices.pushPose()
        // Subtract 0.5 so integer world coords centre the beam on the 4-block
        // intersection (corner offsets ≈ 0.5 put the centre back at x,z).
        matrices.translate(x - 0.5, baseY, z - 0.5)

        val topY = height.toFloat()
        val botY = 0f

        collector.submitCustomGeometry(matrices, FamilyRenderTypes.BEAM) { pose, buf ->
            // One vertical quad spanning two corners, bottom → top alpha.
            fun quad(ax: Float, az: Float, bx: Float, bz: Float, topA: Float, botA: Float) {
                buf.addVertex(pose, ax, topY, az).setColor(r, g, b, topA)
                buf.addVertex(pose, ax, botY, az).setColor(r, g, b, botA)
                buf.addVertex(pose, bx, botY, bz).setColor(r, g, b, botA)
                buf.addVertex(pose, bx, topY, bz).setColor(r, g, b, topA)
            }

            // Inner column — [alpha] at the bottom, fading towards the top.
            val a = alpha.coerceIn(0f, 1f)
            val topA = a * 0.6f
            quad(d4, d5, d6, d7, topA, a)
            quad(d10, d11, d8, d9, topA, a)
            quad(d6, d7, d10, d11, topA, a)
            quad(d8, d9, d4, d5, topA, a)

            // Outer shell — wider column at a quarter of the opacity.
            val oa = 0.25f * a
            val lo = 0.5f - outer; val hi = 0.5f + outer
            quad(lo, lo, hi, lo, oa * 0.6f, oa)
            quad(hi, lo, hi, hi, oa * 0.6f, oa)
            quad(hi, hi, lo, hi, oa * 0.6f, oa)
            quad(lo, hi, lo, lo, oa * 0.6f, oa)
        }

        matrices.popPose()
    }
}
