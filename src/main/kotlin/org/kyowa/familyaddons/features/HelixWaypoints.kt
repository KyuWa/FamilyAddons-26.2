package org.kyowa.familyaddons.features

import com.google.gson.JsonParser
import com.mojang.blaze3d.vertex.PoseStack
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.sounds.SoundEvents
import org.kyowa.familyaddons.util.FaChat
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.world.phys.Vec3
import org.kyowa.familyaddons.FamilyAddons
import org.kyowa.familyaddons.config.FamilyConfigManager
import org.kyowa.familyaddons.util.DevAccess

/**
 * Owner-only: the Big Helix tree route on Torrhus Canyon (imported from a
 * Skyblocker waypoint export, bundled as helix_waypoints.json). Box outlines
 * through walls in the export's colours (green = tree, cyan = etherwarp spot,
 * white = Evasive shop) with a distance label. Shown only for the dev UUID,
 * only on Torrhus Canyon, behind the "Helix Tree Waypoints" toggle in Dev.
 *
 * Route mode: the points are visited in file order (the shop is skipped);
 * only the current stop is drawn. A tracer runs from you to it; getting within [REACH] blocks
 * of it advances to the next one (wrapping), with a ping. `/fa helix
 * next|prev|reset|list` steps manually.
 */
object HelixWaypoints {

    private class Point(val name: String, val x: Int, val y: Int, val z: Int, val r: Float, val g: Float, val b: Float)

    private const val ISLAND = "Torrhus Canyon"
    private val points: List<Point> by lazy { load() }

    @Volatile private var onIsland = false
    private var ticker = 0

    private const val REACH = 6.0
    /** Indices into [points] that form the route (everything but the shop). */
    private val route: List<Int> by lazy { points.indices.filter { !points[it].name.contains("shop", ignoreCase = true) } }
    private var routePos = 0
    private fun target(): Point? = route.getOrNull(routePos)?.let { points[it] }

    private fun load(): List<Point> = try {
        val text = FamilyAddons::class.java.getResourceAsStream("/helix_waypoints.json")!!.bufferedReader().readText()
        JsonParser.parseString(text).asJsonObject.getAsJsonArray("waypoints").map { e ->
            val o = e.asJsonObject
            Point(o.get("name").asString, o.get("x").asInt, o.get("y").asInt, o.get("z").asInt,
                o.get("r").asFloat, o.get("g").asFloat, o.get("b").asFloat)
        }
    } catch (e: Exception) {
        FamilyAddons.LOGGER.warn("HelixWaypoints: could not load helix_waypoints.json: ${e.message}")
        emptyList()
    }

    private fun enabled() = DevAccess.isDev() && FamilyConfigManager.config.dev.helixWaypoints

    fun register() {
        // World change (warp, island hop, relog): start the route over from stop 1.
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register { _, _, _ -> routePos = 0; onIsland = false }
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> routePos = 0; onIsland = false }

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (++ticker >= 20) {
                ticker = 0
                onIsland = enabled() && Waypoints.getCurrentIsland() == ISLAND
            }
            if (!onIsland || !FamilyConfigManager.config.dev.helixTracer) return@register
            val player = client.player ?: return@register
            val t = target() ?: return@register
            val dx = player.x - (t.x + 0.5); val dy = player.y - t.y; val dz = player.z - (t.z + 0.5)
            if (dx * dx + dz * dz <= REACH * REACH && Math.abs(dy) <= REACH) {
                advance(+1)
                player.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 0.8f, 1.4f)
            }
        }
    }

    fun hasWaypoints(): Boolean = onIsland && enabled() && points.isNotEmpty()

    private fun advance(step: Int) {
        if (route.isEmpty()) return
        routePos = ((routePos + step) % route.size + route.size) % route.size
    }

    /** `/fa helix next|prev|reset|list` */
    fun command(arg: String) {
        when (arg.lowercase()) {
            "next" -> { advance(+1); FaChat.send("§aHelix route: now heading to §e${target()?.name}") }
            "prev" -> { advance(-1); FaChat.send("§aHelix route: back to §e${target()?.name}") }
            "reset" -> { routePos = 0; FaChat.send("§aHelix route reset to §e${target()?.name}") }
            else -> {
                FaChat.send("§7Helix route (${route.size} stops), current §e${routePos + 1}§7:")
                route.forEachIndexed { i, idx ->
                    val p = points[idx]
                    val mark = if (i == routePos) "§a> " else "§7  "
                    Minecraft.getInstance().player?.sendSystemMessage(net.minecraft.network.chat.Component.literal("$mark§f${i + 1}. ${p.name} §8@ ${p.x}, ${p.y}, ${p.z}"))
                }
            }
        }
    }

    fun onWorldRender(matrices: PoseStack, collector: SubmitNodeCollector, camera: Camera) {
        if (!hasWaypoints()) return
        val mc = Minecraft.getInstance()
        val p = target() ?: return

        val cam = camera.position()
        matrices.pushPose()
        matrices.translate(-cam.x, -cam.y, -cam.z)

        // Only the current stop is drawn: its box, its label, and the tracer to it.
        val x1 = p.x.toFloat(); val y1 = p.y.toFloat(); val z1 = p.z.toFloat()
        collector.submitCustomGeometry(matrices, FamilyRenderTypes.LINES) { pose, buf -> boxEdges(buf, pose, x1, y1, z1, p.r, p.g, p.b, 1f) }
        collector.submitCustomGeometry(matrices, FamilyRenderTypes.LINES_NO_DEPTH) { pose, buf -> boxEdges(buf, pose, x1, y1, z1, p.r, p.g, p.b, 1f) }
        val centre = Vec3(p.x + 0.5, p.y + 0.5, p.z + 0.5)
        val dist = centre.distanceTo(cam)

        if (FamilyConfigManager.config.dev.helixTracer) {
            // Same anchor as the bestiary tracer: half a block in front of the
            // camera along its own yaw/pitch, so the line starts at the crosshair
            // and does not jitter with the player's tick position.
            val yaw = Math.toRadians(camera.yRot().toDouble())
            val pitch = Math.toRadians(camera.xRot().toDouble())
            val start = Vec3(cam.x - Math.sin(yaw) * Math.cos(pitch) * 0.5, cam.y - Math.sin(pitch) * 0.5, cam.z + Math.cos(yaw) * Math.cos(pitch) * 0.5)
            val d = centre.subtract(start).normalize()
            val c = tracerColor()
            collector.submitCustomGeometry(matrices, FamilyRenderTypes.LINES_NO_DEPTH) { pose, buf ->
                buf.addVertex(pose, start.x.toFloat(), start.y.toFloat(), start.z.toFloat()).setColor(c[0], c[1], c[2], c[3]).setNormal(pose, d.x.toFloat(), d.y.toFloat(), d.z.toFloat()).setLineWidth(3f)
                buf.addVertex(pose, centre.x.toFloat(), centre.y.toFloat(), centre.z.toFloat()).setColor(c[0], c[1], c[2], c[3]).setNormal(pose, d.x.toFloat(), d.y.toFloat(), d.z.toFloat()).setLineWidth(3f)
            }
        }

        val scale = (dist / 10.0).coerceIn(1.0, 5.0).toFloat()
        val code = if (p.g > 0.9f && p.b > 0.9f) "§b" else if (p.g > 0.9f) "§a" else "§f"
        val label = Vec3(p.x + 0.5, p.y + 1.5, p.z + 0.5)
        PearlWaypoints.drawLabel(matrices, collector, label, "§e§l> §7${routePos + 1}/${route.size} $code${p.name} §7(${dist.toInt()}m)", scale, 2)

        matrices.popPose()
    }

    /** Dev "Helix Tracer Color" ("chroma:alpha:r:g:b") → r, g, b, a in 0..1. */
    private fun tracerColor(): FloatArray = try {
        val q = FamilyConfigManager.config.dev.helixTracerColor.split(":")
        floatArrayOf(q[2].toInt() / 255f, q[3].toInt() / 255f, q[4].toInt() / 255f, q[1].toInt() / 255f)
    } catch (e: Exception) { floatArrayOf(1f, 0.67f, 0f, 0.9f) }

    private fun boxEdges(
        buf: com.mojang.blaze3d.vertex.VertexConsumer,
        entry: PoseStack.Pose,
        x1: Float, y1: Float, z1: Float,
        r: Float, g: Float, b: Float, a: Float,
    ) {
        val x2 = x1 + 1f; val y2 = y1 + 1f; val z2 = z1 + 1f
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
