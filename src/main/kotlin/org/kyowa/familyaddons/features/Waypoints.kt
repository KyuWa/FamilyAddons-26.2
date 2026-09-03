package org.kyowa.familyaddons.features

import org.kyowa.familyaddons.COLOR_CODE_REGEX
import org.kyowa.familyaddons.config.FamilyConfigManager
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.Lightmap
import net.minecraft.client.renderer.SubmitNodeCollector
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.Vec3
import org.kyowa.familyaddons.FamilyAddons
import java.io.File

object Waypoints {

    data class Waypoint(val x: Int, val y: Int, val z: Int, var label: String)

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val saveFile = File("config/familyaddons_waypoints.json")
    private val waypointsByIsland = mutableMapOf<String, MutableList<Waypoint>>()

    private var keyWasDown = false

    val waypointR get() = parseColor(0)
    val waypointG get() = parseColor(1)
    val waypointB get() = parseColor(2)

    private fun parseColor(idx: Int): Float {
        return try {
            val parts = FamilyConfigManager.config.waypoints.color.split(":")
            parts[idx + 2].toInt() / 255f
        } catch (e: Exception) {
            when (idx) {
                0 -> FamilyConfigManager.config.waypoints.colorR / 255f
                1 -> FamilyConfigManager.config.waypoints.colorG / 255f
                else -> FamilyConfigManager.config.waypoints.colorB / 255f
            }
        }
    }

    fun register() {
        load()

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (!FamilyConfigManager.config.waypoints.enabled) return@register
            val player = client.player ?: return@register

            if (client.gui.screen() != null) { keyWasDown = false; return@register }

            val keyDown = org.lwjgl.glfw.GLFW.glfwGetKey(
                client.window.handle(),
                FamilyConfigManager.config.waypoints.placeKey
            ) == org.lwjgl.glfw.GLFW.GLFW_PRESS

            if (keyDown && !keyWasDown) {
                val island = getCurrentIsland()
                if (island == null) {
                    chat("§cCan't detect island — open tab list first.")
                } else {
                    val x = player.blockX
                    val y = player.blockY - 1
                    val z = player.blockZ
                    val list = waypointsByIsland.getOrPut(island) { mutableListOf() }
                    val label = (list.size + 1).toString()
                    list.add(Waypoint(x, y, z, label))
                    save()
                    chat("§e$label§a placed on §e$island§a at §b$x, $y, $z")
                }
            }
            keyWasDown = keyDown
        }
    }

    fun hasWaypoints(): Boolean = waypointsByIsland.values.any { it.isNotEmpty() }

    fun onWorldRender(matrices: PoseStack, collector: SubmitNodeCollector, cam: Vec3) {
        if (!FamilyConfigManager.config.waypoints.enabled) return
        val island = getCurrentIsland() ?: return
        val wps = waypointsByIsland[island] ?: return
        if (wps.isEmpty()) return

        // Draw boxes
        fun drawBoxes(alpha: Float, renderType: net.minecraft.client.renderer.rendertype.RenderType) {
            collector.submitCustomGeometry(matrices, renderType) { entry, buf ->
                for (wp in wps) {
                    val x1 = (wp.x - cam.x).toFloat()
                    val y1 = (wp.y - cam.y).toFloat()
                    val z1 = (wp.z - cam.z).toFloat()
                    drawBoxEdges(buf, entry, x1, y1, z1, x1 + 1f, y1 + 1f, z1 + 1f, waypointR, waypointG, waypointB, alpha)
                }
            }
        }

        drawBoxes(1.0f, FamilyRenderTypes.LINES)
        drawBoxes(1.0f, FamilyRenderTypes.LINES_NO_DEPTH)  // full alpha through walls

        // Labels
        if (FamilyConfigManager.config.waypoints.showLabels) {
            for (wp in wps) {
                val dx = wp.x + 0.5 - cam.x
                val dy = wp.y + 1.5 - cam.y
                val dz = wp.z + 0.5 - cam.z
                val dist = Math.sqrt(dx * dx + dy * dy + dz * dz)
                renderLabel(matrices, collector, cam, wp.x + 0.5, wp.y + 1.5, wp.z + 0.5, "${wp.label} (${dist.toInt()}m)", dist)
            }
        }
    }

    private fun renderLabel(
        matrices: PoseStack,
        collector: SubmitNodeCollector,
        cam: Vec3,
        x: Double, y: Double, z: Double,
        text: String,
        dist: Double
    ) {
        val client = Minecraft.getInstance()
        val tr = client.font
        // Scale grows with distance: min=1.0, max=5.0
        val scale = (dist / 10.0).coerceIn(1.0, 5.0).toFloat() * 0.025f

        matrices.pushPose()
        matrices.translate(x - cam.x, y - cam.y, z - cam.z)
        matrices.mulPose(client.gameRenderer.mainCamera().rotation())
        matrices.scale(scale, -scale, scale)
        val w = tr.width(text.replace(COLOR_CODE_REGEX, ""))
        collector.submitText(
            matrices, -w / 2f, 0f,
            Component.literal(text).visualOrderText, true,
            net.minecraft.client.gui.Font.DisplayMode.SEE_THROUGH,
            15728880, -1, 0, 0
        )
        matrices.popPose()
    }

    fun getCurrentIsland(): String? {
        return try {
            val tabList = Minecraft.getInstance().connection?.onlinePlayers ?: return null
            for (entry in tabList) {
                val name = entry.tabListDisplayName?.string?.replace(COLOR_CODE_REGEX, "")?.trim() ?: continue
                if (name.startsWith("Area:")) return name.removePrefix("Area:").trim()
            }
            null
        } catch (e: Exception) { null }
    }

    fun getWaypoints(island: String): List<Waypoint> = waypointsByIsland[island] ?: emptyList()

    fun removeWaypoint(island: String, index: Int): Boolean {
        val list = waypointsByIsland[island] ?: return false
        if (index < 0 || index >= list.size) return false
        list.removeAt(index)
        save()
        return true
    }

    fun clearWaypoints(island: String) {
        waypointsByIsland[island]?.clear()
        save()
    }

    fun renameWaypoint(island: String, index: Int, newName: String): Boolean {
        val list = waypointsByIsland[island] ?: return false
        if (index < 0 || index >= list.size) return false
        list[index].label = newName
        save()
        return true
    }

    private fun load() {
        try {
            if (!saveFile.exists()) return
            val type = object : TypeToken<Map<String, List<Map<String, Any>>>>() {}.type
            val raw = gson.fromJson<Map<String, List<Map<String, Any>>>>(saveFile.readText(), type)
            raw.forEach { (island, wps) ->
                waypointsByIsland[island] = wps.map {
                    Waypoint(
                        (it["x"] as? Double)?.toInt() ?: 0,
                        (it["y"] as? Double)?.toInt() ?: 0,
                        (it["z"] as? Double)?.toInt() ?: 0,
                        it["label"] as? String ?: "?"
                    )
                }.toMutableList()
            }
        } catch (e: Exception) {
            FamilyAddons.LOGGER.warn("Waypoints load error: ${e.message}")
        }
    }

    fun save() {
        try {
            saveFile.parentFile.mkdirs()
            saveFile.writeText(gson.toJson(waypointsByIsland))
        } catch (e: Exception) {
            FamilyAddons.LOGGER.warn("Waypoints save error: ${e.message}")
        }
    }

    private fun chat(msg: String) {
        Minecraft.getInstance().execute {
            Minecraft.getInstance().player?.sendSystemMessage(Component.literal("§6[FA] $msg"))
        }
    }

    private fun drawBoxEdges(
        buf: com.mojang.blaze3d.vertex.VertexConsumer,
        entry: com.mojang.blaze3d.vertex.PoseStack.Pose,
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        r: Float, g: Float, b: Float, a: Float
    ) {
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