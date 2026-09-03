package org.kyowa.familyaddons.features

import com.mojang.blaze3d.vertex.PoseStack
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.item.FallingBlockEntity
import net.minecraft.world.entity.monster.Shulker
import net.minecraft.world.level.block.ShulkerBoxBlock
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.chunk.status.ChunkStatus
import net.minecraft.world.phys.AABB
import org.kyowa.familyaddons.config.FamilyConfigManager

/**
 * Highlights placed shulker boxes (block entities, not the shulker mob — the
 * mob can be highlighted by name via the regular Highlight list). Rescans the
 * chunks around the player once a second, so boxes placed or broken while
 * you stand there appear/disappear without a chunk reload.
 */
object ShulkerBoxHighlight {

    private const val SCAN_INTERVAL = 20
    private const val CHUNK_RADIUS = 5

    private var tick = 0
    private val boxes = mutableListOf<BlockPos>()

    // Shulker boxes that exist as ENTITIES rather than placed blocks —
    // Hypixel's falling-block trick for animated/mob "boxes". Rendered from
    // the live entity position so they track movement between scans.
    private val entityBoxes = mutableListOf<Entity>()

    private fun cfg() = FamilyConfigManager.config.highlight

    // The Highlight/BE master toggle gates this feature too.
    private fun active() = cfg().enabled && cfg().shulkerHighlightEnabled

    /** Shulker entities currently tracked — used by EntityHighlight's tracers. */
    fun trackedEntities(): List<Entity> = if (active()) entityBoxes else emptyList()

    fun register() {
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> boxes.clear(); entityBoxes.clear() }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> boxes.clear(); entityBoxes.clear() }

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (!active()) {
                if (boxes.isNotEmpty()) boxes.clear()
                if (entityBoxes.isNotEmpty()) entityBoxes.clear()
                return@register
            }
            if (tick++ % SCAN_INTERVAL != 0) return@register
            scan(client)
        }
    }

    private fun scan(client: Minecraft) {
        val level = client.level ?: return
        val player = client.player ?: return
        boxes.clear()
        val center = player.blockPosition()
        val pcx = center.x shr 4
        val pcz = center.z shr 4
        for (dx in -CHUNK_RADIUS..CHUNK_RADIUS) {
            for (dz in -CHUNK_RADIUS..CHUNK_RADIUS) {
                val chunk = level.getChunk(pcx + dx, pcz + dz, ChunkStatus.FULL, false)
                    as? LevelChunk ?: continue
                for ((pos, be) in chunk.blockEntities) {
                    if (be is ShulkerBoxBlockEntity) boxes.add(pos.immutable())
                }
            }
        }

        entityBoxes.clear()
        for (e in level.entitiesForRendering()) {
            if (!e.isAlive) continue
            when {
                // The shulker MOB — closed, it renders identically to a box.
                e is Shulker && !e.isInvisible -> entityBoxes.add(e)
                e is FallingBlockEntity && e.blockState.block is ShulkerBoxBlock -> entityBoxes.add(e)
            }
        }
    }

    fun hasBoxes() = (boxes.isNotEmpty() || entityBoxes.isNotEmpty()) && active()

    /** Parse "chroma:alpha:r:g:b" → Float[4] (r,g,b,a) in 0..1. */
    private fun parseColor(s: String, fallback: FloatArray = floatArrayOf(0.8f, 0.4f, 1f, 1f)): FloatArray {
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
        if (!hasBoxes()) return
        val color = parseColor(cfg().shulkerColor)
        val r = color[0]; val g = color[1]; val b = color[2]; val a = color[3]

        val camPos = camera.position()
        matrices.pushPose()
        matrices.translate(-camPos.x, -camPos.y, -camPos.z)

        fun emit(renderType: net.minecraft.client.renderer.rendertype.RenderType, alpha: Float) {
            collector.submitCustomGeometry(matrices, renderType) { pose, buf ->
                for (pos in boxes) {
                    val box = AABB(
                        pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble(),
                        pos.x + 1.0, pos.y + 1.0, pos.z + 1.0
                    )
                    boxEdges(buf, pose, box, r, g, b, alpha)
                }
                for (e in entityBoxes) {
                    if (!e.isAlive) continue
                    boxEdges(buf, pose, e.boundingBox, r, g, b, alpha)
                }
            }
        }
        emit(FamilyRenderTypes.LINES, a)
        emit(FamilyRenderTypes.LINES_NO_DEPTH, a)

        matrices.popPose()
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
