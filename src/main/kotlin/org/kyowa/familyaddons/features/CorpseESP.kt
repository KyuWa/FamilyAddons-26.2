package org.kyowa.familyaddons.features

import org.kyowa.familyaddons.COLOR_CODE_REGEX
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.Lightmap
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.renderer.SubmitNodeCollector
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.phys.Vec3
import org.kyowa.familyaddons.config.FamilyConfigManager
import kotlin.math.sqrt

object CorpseESP {

    data class Corpse(
        val x: Double, val y: Double, val z: Double,
        val label: String,
        val r: Float, val g: Float, val b: Float,
        var looted: Boolean = false
    )

    val cachedCorpses = mutableListOf<Corpse>()

    private val LOOT_MESSAGE_REGEX = Regex("""^\s*(.+?)\s+CORPSE LOOT!\s*$""")

    data class HelmetInfo(val label: String, val r: Float, val g: Float, val b: Float)

    private val HELMET_MAP = mapOf(
        "Lapis Armor Helmet" to HelmetInfo("Lapis",    0.0f,  0.47f, 1.0f),
        "Mineral Helmet"     to HelmetInfo("Tungsten", 1.0f,  1.0f,  1.0f),
        "Yog Helmet"         to HelmetInfo("Umber",    0.71f, 0.38f, 0.13f),
        "Vanguard Helmet"    to HelmetInfo("Vanguard", 0.95f, 0.14f, 0.72f)
    )

    private var lastArea: String? = null
    private var inMineshaft = false

    fun hasCachedCorpses(): Boolean = cachedCorpses.any { !it.looted }

    fun getOutlineColor(entity: net.minecraft.world.entity.Entity): Int {
        val config = FamilyConfigManager.config.mining
        if (!config.corpseESP) return 0
        if (config.corpseDrawingStyle != 1) return 0
        val ex = entity.x; val ey = entity.y; val ez = entity.z
        val corpse = cachedCorpses.firstOrNull { c ->
            !c.looted && Math.abs(c.x - ex) < 1.5 && Math.abs(c.y - ey) < 1.5 && Math.abs(c.z - ez) < 1.5
        } ?: return 0
        val r = (corpse.r * 255).toInt()
        val g = (corpse.g * 255).toInt()
        val b = (corpse.b * 255).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun register() {
        var areaCheckTick = 0
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            var currentArea = lastArea
            if (areaCheckTick++ % 10 == 0) {
                val tabList = client.connection?.onlinePlayers ?: return@register
                currentArea = null
                for (entry in tabList) {
                    val name = entry.tabListDisplayName?.string?.replace(COLOR_CODE_REGEX, "")?.trim() ?: continue
                    if (name.startsWith("Area:")) { currentArea = name.removePrefix("Area:").trim(); break }
                }
            }
            if (currentArea != lastArea) {
                lastArea = currentArea
                if (currentArea == "Mineshaft") {
                    inMineshaft = true; cachedCorpses.clear()
                } else {
                    inMineshaft = false
                }
            }
        }

        var scanTick = 0
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (!FamilyConfigManager.config.mining.corpseESP) return@register
            if (!inMineshaft) return@register
            val world = client.level ?: return@register
            if (scanTick++ % 10 != 0) return@register
            for (entity in world.entitiesForRendering()) {
                if (entity !is ArmorStand) continue
                if (entity.isInvisible) continue
                val ex = entity.x; val ey = entity.y; val ez = entity.z
                val helmet = entity.getItemBySlot(EquipmentSlot.HEAD)
                if (helmet.isEmpty) continue
                val helmetName = helmet.hoverName.string.replace(COLOR_CODE_REGEX, "").trim()
                val info = HELMET_MAP[helmetName] ?: continue
                if (cachedCorpses.none { c ->
                        Math.abs(c.x - ex) < 2 && Math.abs(c.y - ey) < 2 && Math.abs(c.z - ez) < 2
                    }) {
                    cachedCorpses.add(Corpse(ex, ey, ez, info.label, info.r, info.g, info.b))
                }
            }
        }

        ClientReceiveMessageEvents.ALLOW_GAME.register { message, _ ->
            val plain = message.string.replace(COLOR_CODE_REGEX, "").trim()
            val match = LOOT_MESSAGE_REGEX.find(plain)
            if (match != null) {
                val corpseName = match.groupValues[1].trim()
                val client = Minecraft.getInstance()
                val player = client.player ?: return@register true
                val px = player.x; val py = player.y; val pz = player.z
                cachedCorpses
                    .filter { !it.looted && it.label.equals(corpseName, ignoreCase = true) }
                    .minByOrNull { c -> val dx = c.x-px; val dy = c.y-py; val dz = c.z-pz; dx*dx+dy*dy+dz*dz }
                    ?.let { it.looted = true }
                if (FamilyConfigManager.config.mining.corpseAnnounce) {
                    val fx = px.toInt(); val fy = py.toInt(); val fz = pz.toInt()
                    client.execute { player.connection.sendChat("/pc x: $fx, y: $fy, z: $fz | ($corpseName Corpse)") }
                }
            }
            true
        }

        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> cachedCorpses.clear() }
        ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
            cachedCorpses.clear(); lastArea = null; inMineshaft = false
        }
    }

    fun onWorldRender(matrices: PoseStack, collector: SubmitNodeCollector, cam: Vec3) {
        if (!FamilyConfigManager.config.mining.corpseESP) return
        val visible = cachedCorpses.filter { !it.looted }
        if (visible.isEmpty()) return
        if (FamilyConfigManager.config.mining.corpseDrawingStyle == 1) return

        // Pass 1: with depth, full alpha
        fun draw(alpha: Float, renderType: net.minecraft.client.renderer.rendertype.RenderType) {
            collector.submitCustomGeometry(matrices, renderType) { entry, buf ->
                for (c in visible) {
                    drawBoxEdges(buf, entry,
                        (c.x - 0.5 - cam.x).toFloat(), (c.y - cam.y).toFloat(), (c.z - 0.5 - cam.z).toFloat(),
                        (c.x + 0.5 - cam.x).toFloat(), (c.y + 2.0 - cam.y).toFloat(), (c.z + 0.5 - cam.z).toFloat(),
                        c.r, c.g, c.b, alpha)
                }
            }
        }

        draw(1.0f, FamilyRenderTypes.LINES)
        draw(1.0f, FamilyRenderTypes.LINES_NO_DEPTH)  // full alpha through walls too

        // Labels
        for (c in visible) {
            val dx = c.x - cam.x; val dy = c.y - cam.y; val dz = c.z - cam.z
            val dist = sqrt(dx*dx + dy*dy + dz*dz)
            renderLabel(matrices, collector, cam, c.x, c.y + 2.2, c.z, "§f${c.label} §7(${dist.toInt()}m)", dist)
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

    internal fun drawBoxEdges(
        buf: VertexConsumer,
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