package org.kyowa.familyaddons.features

import com.mojang.blaze3d.vertex.PoseStack
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.event.player.AttackBlockCallback
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.minecraft.world.InteractionResult
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.world.entity.Display
import net.minecraft.world.phys.Vec3
import org.kyowa.familyaddons.config.FamilyConfigManager
import org.kyowa.familyaddons.util.HypixelLocation

/**
 * Critter Safari "floor drops": a little pile of item-display entities on the
 * ground with green sparkles over it (entity dump 2026-09-09: three nameless
 * item_display entities within half a block of each other, no other entity).
 * Outlines the block the pile sits on so it stands out.
 *
 * A pile is confirmed when at least two nameless item displays sit within
 * [CLUSTER_RADIUS] of each other AND a happy-villager (green sparkle) burst
 * was seen within [SPARKLE_RADIUS] of them in the last [SPARKLE_TTL_MS]. The
 * outline then stays for [HOLD_MS] after the last confirmation, so the
 * periodic sparkles do not make it flicker.
 *
 * It goes away the moment the drop is taken: the item displays vanish, or
 * the player hits / clicks the outlined block. A taken block is ignored for
 * [CLAIM_TTL_MS] so lingering displays cannot bring the outline back.
 */
object FloorDropHighlight {

    private const val CLUSTER_RADIUS = 1.2
    private const val SPARKLE_RADIUS = 2.0
    private const val SPARKLE_TTL_MS = 6000L
    private const val HOLD_MS = 8000L
    private const val SCAN_INTERVAL = 5
    private const val CLAIM_TTL_MS = 10000L

    private data class Burst(val pos: Vec3, val at: Long)
    private class Target(val block: BlockPos, var lastSeen: Long)

    private val bursts = ArrayDeque<Burst>()
    private val targets = HashMap<BlockPos, Target>()
    private val claimed = HashMap<BlockPos, Long>()
    private var tick = 0

    private fun cfg() = FamilyConfigManager.config.highlight
    /** Only in the Critter Safari: the Mod API reports the area as "Safari" (tab list: "Critter Safari"). */
    private fun inSafari() = HypixelLocation.areaName()?.contains("safari", ignoreCase = true) == true
    private fun active() = cfg().enabled && cfg().floorDropsEnabled && inSafari()

    fun hasTargets(): Boolean = active() && targets.isNotEmpty()

    /** From ParticlePacketMixin on the main thread. */
    fun onParticle(packet: ClientboundLevelParticlesPacket) {
        if (!active()) return
        val key = BuiltInRegistries.PARTICLE_TYPE.getKey(packet.particle.type) ?: return
        if (key.path != "happy_villager") return
        synchronized(bursts) {
            bursts.addLast(Burst(Vec3(packet.x, packet.y, packet.z), System.currentTimeMillis()))
            while (bursts.size > 256) bursts.removeFirst()
        }
    }

    fun register() {
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> clear() }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> clear() }
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (!active()) { if (targets.isNotEmpty()) clear(); return@register }
            if (tick++ % SCAN_INTERVAL != 0) return@register
            scan(client)
        }
        // Clicking the outlined block (or the drop sitting on it) = we took it.
        AttackBlockCallback.EVENT.register { _, _, _, pos, _ -> claimNear(pos); InteractionResult.PASS }
        UseBlockCallback.EVENT.register { _, _, _, hit -> claimNear(hit.blockPos); InteractionResult.PASS }
    }

    private fun clear() {
        targets.clear()
        claimed.clear()
        synchronized(bursts) { bursts.clear() }
    }

    private fun claim(block: BlockPos) {
        targets.remove(block)
        claimed[block] = System.currentTimeMillis()
    }

    /** Clicked [pos] or a block right next to it (the pile can sit on the edge). */
    private fun claimNear(pos: BlockPos) {
        if (targets.isEmpty()) return
        for (b in targets.keys.toList()) if (b.distManhattan(pos) <= 1) claim(b)
    }

    private fun sparkleNear(pos: Vec3, now: Long): Boolean = synchronized(bursts) {
        while (bursts.isNotEmpty() && now - bursts.first().at > SPARKLE_TTL_MS) bursts.removeFirst()
        bursts.any { it.pos.distanceTo(pos) <= SPARKLE_RADIUS }
    }

    private fun scan(client: Minecraft) {
        val level = client.level ?: return
        val now = System.currentTimeMillis()
        val displays = ArrayList<Vec3>()
        for (e in level.entitiesForRendering()) {
            if (e is Display.ItemDisplay && e.isAlive && e.customName == null) displays.add(e.position())
        }
        claimed.values.removeIf { now - it > CLAIM_TTL_MS }
        // Clusters: any display with at least one other display close by.
        for (p in displays) {
            val mates = displays.count { it !== p && it.distanceTo(p) <= CLUSTER_RADIUS }
            if (mates < 1) continue
            if (!sparkleNear(p, now)) continue
            val block = groundBelow(level, p) ?: continue
            if (claimed.containsKey(block)) continue
            targets.getOrPut(block) { Target(block, now) }.lastSeen = now
        }
        targets.values.removeIf { now - it.lastSeen > HOLD_MS }
        // Taken: the pile's displays are gone.
        targets.keys.toList().forEach { block ->
            val center = Vec3.atCenterOf(block)
            if (displays.none { it.distanceTo(center) <= 1.5 }) claim(block)
        }
    }

    /** The block the pile rests on: first non-air block at or below the pile, up to 3 down. */
    private fun groundBelow(level: net.minecraft.client.multiplayer.ClientLevel, p: Vec3): BlockPos? {
        val start = BlockPos.containing(p)
        for (dy in 0..3) {
            val pos = start.below(dy)
            if (!level.getBlockState(pos).isAir) return pos
        }
        return null
    }

    // ── Render ──────────────────────────────────────────────────────────

    private fun parseColor(s: String, fallback: FloatArray = floatArrayOf(0.31f, 1f, 0.31f, 1f)): FloatArray = try {
        val p = s.split(":")
        floatArrayOf(p[2].toInt() / 255f, p[3].toInt() / 255f, p[4].toInt() / 255f, p[1].toInt() / 255f)
    } catch (e: Exception) { fallback }

    fun onWorldRender(matrices: PoseStack, collector: SubmitNodeCollector, camera: Camera) {
        if (!hasTargets()) return
        val mc = Minecraft.getInstance()

        val c = parseColor(cfg().floorDropsColor)
        val cam = camera.position()
        matrices.pushPose()
        matrices.translate(-cam.x, -cam.y, -cam.z)
        for (t in targets.values) {
            val x1 = t.block.x.toFloat(); val y1 = t.block.y.toFloat(); val z1 = t.block.z.toFloat()
            collector.submitCustomGeometry(matrices, FamilyRenderTypes.LINES) { pose, buf -> boxEdges(buf, pose, x1, y1, z1, c[0], c[1], c[2], c[3]) }
            collector.submitCustomGeometry(matrices, FamilyRenderTypes.LINES_NO_DEPTH) { pose, buf -> boxEdges(buf, pose, x1, y1, z1, c[0], c[1], c[2], c[3] * 0.6f) }
        }
        matrices.popPose()
    }

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
            buf.addVertex(entry, e[0], e[1], e[2]).setColor(r, g, b, a).setNormal(entry, dx, dy, dz).setLineWidth(2.5f)
            buf.addVertex(entry, e[3], e[4], e[5]).setColor(r, g, b, a).setNormal(entry, dx, dy, dz).setLineWidth(2.5f)
        }
    }

    fun debugDump(): String = "§6[FA FloorDrops] §7active=${active()} targets=${targets.size} bursts=${synchronized(bursts) { bursts.size }}"
}
