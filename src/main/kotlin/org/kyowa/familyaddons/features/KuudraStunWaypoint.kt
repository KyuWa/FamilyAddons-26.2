package org.kyowa.familyaddons.features

import com.mojang.blaze3d.vertex.PoseStack
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.kyowa.familyaddons.COLOR_CODE_REGEX
import org.kyowa.familyaddons.config.FamilyConfigManager

/**
 * Kuudra stun-pod waypoint: shows a wireframe box on the chosen pod after
 * buying Human Cannonball, offset-relative until you enter the belly.
 * Ported from pawsup-1.2.5 StunWaypoint.
 *
 * Before the belly-entry teleport the box is drawn relative to the player
 * using the (pod - enter) offset so it lines up for the cannonball shot;
 * once inside the belly it's drawn at the pod's real coordinates.
 */
object KuudraStunWaypoint {

    private const val RUN_START_MSG =
        "[NPC] Elle: Okay adventurers, I will go and fish up Kuudra!"

    // Teleport destination when you enter Kuudra's belly
    private val ENTER = Vec3(-161.0, 49.0, -186.0)

    // Pod positions, index-aligned with the "Pick A Pod" dropdown
    private val POD_COORDS = listOf(
        Vec3(-152.5, 27.0, -172.5), // Close Left
        Vec3(-167.5, 28.0, -167.5), // Close Right
        Vec3(-155.5, 28.0, -156.5), // Far Middle
    )

    private var showWaypoint = false
    private var inBelly = false

    private fun cfg() = FamilyConfigManager.config.kuudra

    fun hasWaypoint(): Boolean {
        if (!cfg().stunWaypointEnabled || !showWaypoint) return false
        return Minecraft.getInstance().player != null
    }

    private fun reset() {
        showWaypoint = false
        inBelly = false
    }

    /** Called from PlayerPositionPacketMixin on every server teleport. */
    fun onTeleport(pos: Vec3) {
        if (!cfg().stunWaypointEnabled) return
        // Detect the belly-entry teleport by matching integer coords against ENTER.
        if (pos.x.toInt() == ENTER.x.toInt() &&
            pos.y.toInt() == ENTER.y.toInt() &&
            pos.z.toInt() == ENTER.z.toInt()
        ) {
            inBelly = true
        }
    }

    fun register() {
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> reset() }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> reset() }

        ClientReceiveMessageEvents.ALLOW_GAME.register { message, _ ->
            if (cfg().stunWaypointEnabled) {
                val plain = message.string.replace(COLOR_CODE_REGEX, "").trim()
                when {
                    plain == RUN_START_MSG -> reset()
                    plain == "You purchased Human Cannonball!" -> showWaypoint = true
                    plain.contains("destroyed one of Kuudra's pods!") -> reset()
                }
            }
            true
        }
    }

    /** Parse "chroma:alpha:r:g:b" → Float[4] (r,g,b,a) in 0..1. */
    private fun parseColor(s: String, fallback: FloatArray = floatArrayOf(0.33f, 1f, 1f, 1f)): FloatArray {
        return try {
            val p = s.split(":")
            floatArrayOf(
                p[2].toInt() / 255f,
                p[3].toInt() / 255f,
                p[4].toInt() / 255f,
                p[1].toInt() / 255f
            )
        } catch (e: Exception) { fallback }
    }

    fun onWorldRender(matrices: PoseStack, collector: SubmitNodeCollector, camera: Camera) {
        if (!hasWaypoint()) return
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return

        val podPos = POD_COORDS[cfg().stunPod.coerceIn(0, POD_COORDS.size - 1)]

        val pos = if (!inBelly) {
            val diff = podPos.subtract(ENTER)
            Vec3(player.x + diff.x, player.y + diff.y, player.z + diff.z)
        } else {
            podPos
        }

        val box = AABB(
            pos.x - 0.5, pos.y, pos.z - 0.5,
            pos.x + 0.5, pos.y + 1.0, pos.z + 0.5,
        )
        val color = parseColor(cfg().stunWaypointColor)

        val camPos = camera.position()
        matrices.pushPose()
        matrices.translate(-camPos.x, -camPos.y, -camPos.z)
        drawWireframeBox(matrices, collector, box, color)
        matrices.popPose()
    }

    /** Wireframe outline of an AABB. Drawn with depth, then through walls at low alpha. */
    private fun drawWireframeBox(
        matrices: PoseStack,
        collector: SubmitNodeCollector,
        box: AABB,
        color: FloatArray
    ) {
        val r = color[0]; val g = color[1]; val b = color[2]; val a = color[3]
        // Solid in front
        collector.submitCustomGeometry(matrices, FamilyRenderTypes.LINES) { pose, buf ->
            boxEdges(buf, pose, box, r, g, b, a)
        }
        // Faded through walls
        collector.submitCustomGeometry(matrices, FamilyRenderTypes.LINES_NO_DEPTH) { pose, buf ->
            boxEdges(buf, pose, box, r, g, b, a)
        }
    }

    private fun boxEdges(
        buf: com.mojang.blaze3d.vertex.VertexConsumer,
        entry: PoseStack.Pose,
        box: AABB,
        r: Float, g: Float, b: Float, a: Float
    ) {
        val x1 = box.minX.toFloat(); val y1 = box.minY.toFloat(); val z1 = box.minZ.toFloat()
        val x2 = box.maxX.toFloat(); val y2 = box.maxY.toFloat(); val z2 = box.maxZ.toFloat()
        val edges = arrayOf(
            floatArrayOf(x1,y1,z1,x2,y1,z1), floatArrayOf(x2,y1,z1,x2,y1,z2),
            floatArrayOf(x2,y1,z2,x1,y1,z2), floatArrayOf(x1,y1,z2,x1,y1,z1),
            floatArrayOf(x1,y2,z1,x2,y2,z1), floatArrayOf(x2,y2,z1,x2,y2,z2),
            floatArrayOf(x2,y2,z2,x1,y2,z2), floatArrayOf(x1,y2,z2,x1,y2,z1),
            floatArrayOf(x1,y1,z1,x1,y2,z1), floatArrayOf(x2,y1,z1,x2,y2,z1),
            floatArrayOf(x2,y1,z2,x2,y2,z2), floatArrayOf(x1,y1,z2,x1,y2,z2)
        )
        for (e in edges) {
            val dx = e[3]-e[0]; val dy = e[4]-e[1]; val dz = e[5]-e[2]
            buf.addVertex(entry, e[0], e[1], e[2]).setColor(r, g, b, a).setNormal(entry, dx, dy, dz).setLineWidth(2.0f)
            buf.addVertex(entry, e[3], e[4], e[5]).setColor(r, g, b, a).setNormal(entry, dx, dy, dz).setLineWidth(2.0f)
        }
    }
}
