package org.kyowa.familyaddons.features

import com.mojang.blaze3d.vertex.PoseStack
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import org.kyowa.familyaddons.COLOR_CODE_REGEX
import org.kyowa.familyaddons.config.FamilyConfigManager

/**
 * Highlights sparkling critters even when their nametag is hidden (critter
 * nametags only appear after interacting with them).
 *
 * Detection, in order of confidence:
 *  1. Name says "sparkling" (works once the nametag is visible).
 *  2. Enchant glint on any equipment piece (Hypixel often marks shiny
 *     variants with a foiled head item).
 *  3. Sparkle-particle correlation: the server keeps emitting sparkle
 *     particles at the critter — an entity with repeated sparkle bursts
 *     right on top of it within the last few seconds is marked.
 *
 * /fa critterdump logs nearby entities + their particle counts so the
 * heuristics can be tuned against the real thing.
 */
object SparklingCritterHighlight {

    private const val SCAN_INTERVAL = 10
    private const val PARTICLE_TTL_MS = 3000L
    private const val PARTICLE_RADIUS = 1.75
    private const val MIN_BURSTS = 3

    // Particle types that read as "sparkle". Refine with /fa critterdump data.
    private val SPARKLE_TYPES = setOf(
        "end_rod", "firework", "electric_spark", "happy_villager",
        "enchant", "wax_on", "wax_off", "scrape", "totem_of_undying", "glow"
    )

    private data class Burst(val pos: Vec3, val at: Long)

    private val bursts = ArrayDeque<Burst>()
    private val sparkling = mutableListOf<Entity>()
    private var tick = 0

    private fun cfg() = FamilyConfigManager.config.highlight
    private fun active() = cfg().enabled && cfg().sparklingHighlightEnabled

    fun trackedEntities(): List<Entity> = if (active()) sparkling else emptyList()

    fun hasTargets() = sparkling.isNotEmpty() && active()

    /** Called from ParticlePacketMixin on the main thread. */
    fun onParticle(packet: ClientboundLevelParticlesPacket) {
        if (!active()) return
        val key = BuiltInRegistries.PARTICLE_TYPE.getKey(packet.particle.type) ?: return
        if (key.path !in SPARKLE_TYPES) return
        synchronized(bursts) {
            bursts.addLast(Burst(Vec3(packet.x, packet.y, packet.z), System.currentTimeMillis()))
            while (bursts.size > 256) bursts.removeFirst()
        }
    }

    fun register() {
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> clear() }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> clear() }

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (!active()) {
                if (sparkling.isNotEmpty()) clear()
                return@register
            }
            if (tick++ % SCAN_INTERVAL != 0) return@register
            scan(client)
        }
    }

    private fun clear() {
        sparkling.clear()
        synchronized(bursts) { bursts.clear() }
    }

    private fun burstsNear(pos: Vec3, now: Long): Int {
        synchronized(bursts) {
            while (bursts.isNotEmpty() && now - bursts.first().at > PARTICLE_TTL_MS) bursts.removeFirst()
            return bursts.count { it.pos.distanceTo(pos) <= PARTICLE_RADIUS }
        }
    }

    private fun isSparkling(entity: Entity, now: Long): Boolean {
        // 1. Visible nametag says sparkling.
        val name = entity.customName?.string?.replace(COLOR_CODE_REGEX, "")?.lowercase()
        if (name != null && name.contains("sparkling")) return true

        // 2. Enchant glint on any equipment piece.
        if (entity is LivingEntity) {
            for (slot in EquipmentSlot.entries) {
                val stack = try { entity.getItemBySlot(slot) } catch (e: Exception) { continue }
                if (!stack.isEmpty && stack.hasFoil()) return true
            }
        }

        // 3. Repeated sparkle bursts on top of the entity.
        return burstsNear(entity.position(), now) >= MIN_BURSTS
    }

    private fun scan(client: Minecraft) {
        val level = client.level ?: return
        sparkling.clear()
        val now = System.currentTimeMillis()
        for (entity in level.entitiesForRendering()) {
            if (!entity.isAlive || entity is Player) continue
            if (isSparkling(entity, now)) sparkling.add(entity)
        }
    }

    /** Parse "chroma:alpha:r:g:b" → Float[4] (r,g,b,a) in 0..1. */
    private fun parseColor(s: String, fallback: FloatArray = floatArrayOf(1f, 0.9f, 0.47f, 1f)): FloatArray {
        return try {
            val p = s.split(":")
            floatArrayOf(p[2].toInt() / 255f, p[3].toInt() / 255f, p[4].toInt() / 255f, p[1].toInt() / 255f)
        } catch (e: Exception) { fallback }
    }

    fun onWorldRender(matrices: PoseStack, collector: SubmitNodeCollector, camera: Camera) {
        if (!hasTargets()) return
        val color = parseColor(cfg().sparklingColor)
        val r = color[0]; val g = color[1]; val b = color[2]; val a = color[3]

        val camPos = camera.position()
        matrices.pushPose()
        matrices.translate(-camPos.x, -camPos.y, -camPos.z)

        fun emit(renderType: net.minecraft.client.renderer.rendertype.RenderType) {
            collector.submitCustomGeometry(matrices, renderType) { pose, buf ->
                for (e in sparkling) {
                    if (!e.isAlive) continue
                    val box = e.boundingBox.inflate(0.05)
                    EntityHighlight.drawBoxEdges(buf, pose,
                        box.minX.toFloat(), box.minY.toFloat(), box.minZ.toFloat(),
                        box.maxX.toFloat(), box.maxY.toFloat(), box.maxZ.toFloat(),
                        r, g, b, a)
                }
            }
        }
        emit(FamilyRenderTypes.LINES)
        emit(FamilyRenderTypes.LINES_NO_DEPTH)

        matrices.popPose()
    }

    /** /fa critterdump — log nearby entities + sparkle data for tuning. */
    fun dumpNearby() {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val level = mc.level ?: return
        val now = System.currentTimeMillis()
        val sb = StringBuilder("==== Critter dump @ ${java.time.LocalDateTime.now()} ====\n")
        var count = 0
        for (e in level.entitiesForRendering()) {
            if (e === player || !e.isAlive) continue
            if (e.distanceTo(player) > 8f) continue
            count++
            sb.append("${e.javaClass.simpleName} id=${e.id} @ ${"%.1f".format(e.x)},${"%.1f".format(e.y)},${"%.1f".format(e.z)}\n")
            sb.append("  name=${e.name.string} custom=${e.customName?.string} invisible=${e.isInvisible}\n")
            if (e is LivingEntity) {
                for (slot in EquipmentSlot.entries) {
                    val stack = try { e.getItemBySlot(slot) } catch (ex: Exception) { continue }
                    if (!stack.isEmpty) sb.append("  $slot: ${stack.item} foil=${stack.hasFoil()}\n")
                }
            }
            sb.append("  sparkleBursts(last 3s, ${PARTICLE_RADIUS}b): ${burstsNear(e.position(), now)}\n")
        }
        sb.append("total: $count entities within 8 blocks\n\n")
        try {
            val file = java.io.File(mc.gameDirectory, "config/familyaddons/critter_dump.txt")
            file.parentFile.mkdirs()
            file.appendText(sb.toString())
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "§6[FA] §a$count entities dumped §7→ §fconfig/familyaddons/critter_dump.txt"))
        } catch (e: Exception) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§6[FA] §cDump failed: ${e.message}"))
        }
    }
}
