package org.kyowa.familyaddons.features.safari

import com.mojang.blaze3d.vertex.PoseStack
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.event.player.AttackBlockCallback
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.core.BlockPos
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.kyowa.familyaddons.COLOR_CODE_REGEX
import org.kyowa.familyaddons.config.FamilyConfigManager
import org.kyowa.familyaddons.features.FamilyRenderTypes
import org.kyowa.familyaddons.util.HypixelLocation
import java.util.UUID

/**
 * The eleven places in the Safari Forest where a bee block can sit.
 *
 * All eleven are marked from the start of a run, since the positions are fixed and
 * give nothing away. What is at one does, so a spot only changes state once you can
 * actually see it: a bee nest recolours the mark, a beehive or an empty spot clears
 * it. Nothing is read through a wall.
 *
 * A nest is also crossed off by punching it and getting a bee. The punch alone is not
 * the signal, because an already-emptied nest can be hit all day: the mark goes only
 * when a bee that was not there before turns up next to the spot within a few seconds.
 *
 * Positions and the state machine are adapted from Critter Safari Tracker by Rok
 * (https://github.com/MrCloudy2/critterMod, MIT).
 */
object BeeSpotHighlight {

    private enum class Status { UNCHECKED, NEST, CLEARED }

    private val SPOTS: List<BlockPos> = listOf(
        BlockPos(-17, 68, 41), BlockPos(-22, 68, 39),
        BlockPos(23, 70, 46), BlockPos(25, 70, 41),
        BlockPos(-9, 81, 58), BlockPos(-1, 81, 60),
        BlockPos(15, 88, 31), BlockPos(20, 88, 33),
        BlockPos(-1, 84, 9),
        BlockPos(-13, 103, 21), BlockPos(-11, 103, 13),
    )

    private const val SCAN_INTERVAL = 10
    private const val SEE_RANGE = 48.0
    private const val PUNCH_RADIUS = 2.5
    private const val BEE_RADIUS = 6.0
    private const val PUNCH_WINDOW_MS = 4000L

    private val seen = HashMap<BlockPos, Status>()
    private var punched: BlockPos? = null
    private var punchedAt = 0L
    private val beesBefore = HashSet<UUID>()
    private var tick = 0

    private fun cfg() = FamilyConfigManager.config.safari
    private fun inSafari() = HypixelLocation.areaName()?.contains("safari", ignoreCase = true) == true
    private fun active() = cfg().enabled && cfg().beeSpots && inSafari()

    fun hasTargets(): Boolean = active() && SPOTS.any { (seen[it] ?: Status.UNCHECKED) != Status.CLEARED }

    fun register() {
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> reset() }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> reset() }
        AttackBlockCallback.EVENT.register { _, _, _, pos, _ -> if (active()) onAttack(pos); InteractionResult.PASS }
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (!active()) return@register
            checkForBee(client)
            if (++tick < SCAN_INTERVAL) return@register
            tick = 0
            resolveVisible(client)
        }
    }

    fun reset() {
        seen.clear(); punched = null; beesBefore.clear()
    }

    // ── sight ──────────────────────────────────────────────────────────────

    private fun resolveVisible(client: Minecraft) {
        val level = client.level ?: return
        val player = client.player ?: return
        for (pos in SPOTS) {
            if (seen.containsKey(pos)) continue
            if (!level.isLoaded(pos)) continue
            if (!canSee(player, pos)) continue
            seen[pos] = if (level.getBlockState(pos).`is`(Blocks.BEE_NEST)) Status.NEST else Status.CLEARED
        }
    }

    /** True when a ray from the eyes reaches the spot (or would, if it is empty air). */
    private fun canSee(player: Entity, pos: BlockPos): Boolean {
        val eye = player.eyePosition
        val target = Vec3.atCenterOf(pos)
        if (eye.distanceTo(target) > SEE_RANGE) return false
        val hit = player.level().clip(ClipContext(eye, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
        return hit.type == HitResult.Type.MISS || (hit is net.minecraft.world.phys.BlockHitResult && hit.blockPos == pos)
    }

    // ── punch, then a bee ────────────────────────────────────────────────────

    private fun isBee(e: Entity): Boolean =
        net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.type).path == "bee" ||
            (e.customName?.string?.replace(COLOR_CODE_REGEX, "")?.contains("Honeybug", ignoreCase = true) == true)

    private fun onAttack(pos: BlockPos) {
        val spot = SPOTS.filter { it.distSqr(pos) <= PUNCH_RADIUS * PUNCH_RADIUS }.minByOrNull { it.distSqr(pos) } ?: return
        punched = spot
        punchedAt = System.currentTimeMillis()
        beesBefore.clear()
        val level = Minecraft.getInstance().level ?: return
        for (e in level.entitiesForRendering()) if (isBee(e)) beesBefore.add(e.uuid)
    }

    private fun checkForBee(client: Minecraft) {
        val spot = punched ?: return
        if (System.currentTimeMillis() - punchedAt > PUNCH_WINDOW_MS) { punched = null; return }
        val level = client.level ?: return
        val centre = Vec3.atCenterOf(spot)
        for (e in level.entitiesForRendering()) {
            if (!isBee(e) || e.uuid in beesBefore) continue
            if (e.position().distanceToSqr(centre) > BEE_RADIUS * BEE_RADIUS) continue
            seen[spot] = Status.CLEARED
            punched = null
            return
        }
    }

    // ── render ─────────────────────────────────────────────────────────────

    private fun parseColor(s: String, fallback: FloatArray): FloatArray = try {
        val p = s.split(":")
        floatArrayOf(p[2].toInt() / 255f, p[3].toInt() / 255f, p[4].toInt() / 255f, p[1].toInt() / 255f)
    } catch (e: Exception) { fallback }

    fun onWorldRender(matrices: PoseStack, collector: SubmitNodeCollector, camera: Camera) {
        if (!hasTargets()) return
        val unchecked = parseColor(cfg().beeSpotColor, floatArrayOf(0.33f, 1f, 0.33f, 1f))
        val nest = parseColor(cfg().beeNestColor, floatArrayOf(1f, 0.8f, 0.13f, 1f))
        val cam = camera.position()
        matrices.pushPose()
        matrices.translate(-cam.x, -cam.y, -cam.z)
        for (pos in SPOTS) {
            val c = when (seen[pos] ?: Status.UNCHECKED) {
                Status.CLEARED -> continue
                Status.NEST -> nest
                Status.UNCHECKED -> unchecked
            }
            val x1 = pos.x.toFloat(); val y1 = pos.y.toFloat(); val z1 = pos.z.toFloat()
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

    fun describe(): String {
        val nests = SPOTS.count { seen[it] == Status.NEST }
        val unchecked = SPOTS.count { !seen.containsKey(it) }
        return "$nests nests, $unchecked unchecked, of ${SPOTS.size}" + (punched?.let { " (armed at ${it.toShortString()})" } ?: "")
    }
}
