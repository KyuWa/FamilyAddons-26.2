package org.kyowa.familyaddons.features

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.Camera
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.ambient.Bat
import net.minecraft.world.entity.boss.wither.WitherBoss
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Player
import org.kyowa.familyaddons.COLOR_CODE_REGEX
import org.kyowa.familyaddons.config.FamilyConfigManager

/**
 * Dungeon mob highlight, ported from OdinFabric's Highlight module
 * (odtheking, BSD-3-Clause). Uses the glow-outline drawing style via
 * [org.kyowa.familyaddons.mixin.EntityOutlineMixin].
 *
 * Fully independent of the Highlight category — gated only on its own
 * toggles under Dungeons. Highlights:
 *  - starred dungeon mobs (✯ nametags over known spawn names, plus the
 *    Shadow Assassin player-mobs which carry no nametag stand)
 *  - powered withers (Necron, Goldor, Storm, Maxor)
 *  - dungeon bats (excluding spirit-sceptre bats that spawn on the player)
 *
 * "Behind Walls" off = an outline only shows while you have line of sight
 * to the mob; on = outlines glow through walls (vanilla glow behaviour).
 */
object DungeonHighlight {

    // Backup rebuild; the packet hook (DungeonEntityPacketMixin) classifies
    // new mobs instantly, this only catches anything it missed.
    private const val SCAN_INTERVAL = 4

    private val dungeonMobSpawns = hashSetOf(
        "Lurker", "Dreadlord", "Souleater", "Zombie", "Skeleton", "Skeletor",
        "Sniper", "Super Archer", "Spider", "Fels", "Withermancer",
        "Lost Adventurer", "Angry Archaeologist", "Frozen Adventurer", "Shadow Assassin"
    )
    private val starredRegex = Regex("^.*✯ .*\\d{1,3}(?:,\\d{3})*(?:\\.\\d+)?[kM]?❤$")

    private var tick = 0
    private var inDungeon = false

    // entity id -> ARGB outline color, rebuilt every scan
    private val outlineColors = hashMapOf<Int, Int>()

    // Spirit-sceptre bats spawn right on top of the player; remember and skip
    // them. seenBatIds tracks which bats were already distance-classified so a
    // bat that later flies close to the player isn't misclassified.
    private val spiritSceptreIds = hashSetOf<Int>()
    private val seenBatIds = hashSetOf<Int>()

    private fun cfg() = FamilyConfigManager.config.dungeons

    fun register() {
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> clear() }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> clear() }

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (!cfg().dungeonHighlightEnabled) {
                if (outlineColors.isNotEmpty()) clear()
                return@register
            }
            if (tick++ % SCAN_INTERVAL != 0) return@register
            scan(client)
        }
    }

    private fun clear() {
        outlineColors.clear()
        spiritSceptreIds.clear()
        seenBatIds.clear()
        inDungeon = false
    }

    private fun scan(client: Minecraft) {
        inDungeon = DevTools.getScoreboardLines(client)
            .any { it.contains("The Catacombs", ignoreCase = true) }
        if (!inDungeon) {
            if (outlineColors.isNotEmpty()) clear()
            return
        }

        val level = client.level ?: return
        val player = client.player ?: return

        outlineColors.clear()
        for (entity in level.entitiesForRendering()) classify(entity, player)
    }

    /**
     * Packet hook: an entity just spawned or had its metadata (name) set.
     * Classify it right away so a starred mob lights up the tick its nametag
     * arrives instead of up to a scan later.
     */
    fun onEntityUpdate(id: Int) {
        if (!inDungeon || !cfg().dungeonHighlightEnabled) return
        val client = Minecraft.getInstance()
        val level = client.level ?: return
        val player = client.player ?: return
        val entity = level.getEntity(id) ?: return
        classify(entity, player)
    }

    private fun classify(entity: Entity, player: Player) {
        val config = cfg()
        val starredColor = parseColor(config.dungeonHighlightColor, 0xFFFFFFFF.toInt())
        val witherColor  = parseColor(config.dungeonWitherColor,    0xFFFF0000.toInt())
        val batColor     = parseColor(config.dungeonBatColor,       0xFF00FFFF.toInt())
        run {
            val entity = entity
            if (!entity.isAlive) return
            when {
                config.dungeonHighlightWithers && entity is WitherBoss && entity.isPowered -> {
                    outlineColors[entity.id] = witherColor
                }

                config.dungeonHighlightBats && entity is Bat && !entity.isPassenger && !entity.isInvisible -> {
                    if (seenBatIds.add(entity.id)) {
                        // Freshly spawned bat right next to the player is a
                        // spirit sceptre proc, not a dungeon secret bat. The
                        // radius is a bit wider than Odin's 1.0 because our
                        // scan runs every 10 ticks, not on the spawn packet.
                        if (player.distanceTo(entity) < 2.5f) {
                            spiritSceptreIds.add(entity.id)
                        }
                    }
                    if (entity.id !in spiritSceptreIds) outlineColors[entity.id] = batColor
                }

                config.dungeonHighlightStar && entity is Player && entity != player &&
                        !isRealPlayer(entity) && entity.gameProfile.name.contains("Shadow Assassin") -> {
                    outlineColors[entity.id] = starredColor
                }

                config.dungeonHighlightStar && entity is ArmorStand -> {
                    val rawName = entity.customName?.string?.replace(COLOR_CODE_REGEX, "") ?: return
                    if (dungeonMobSpawns.any(rawName::contains) && starredRegex.matches(rawName)) {
                        resolveMob(entity)?.let { outlineColors[it.id] = starredColor }
                    }
                }
            }
        }
    }

    /** Render-distance override (DungeonOutlineRangeMixin): is this mob one we outline? */
    fun isHighlighted(entity: Entity): Boolean =
        inDungeon && cfg().dungeonHighlightEnabled && outlineColors.containsKey(entity.id)

    // ── Boxes at any distance ─────────────────────────────────────────
    // The glow outline only exists for entities Minecraft actually renders, so
    // a starred mob past the entity render distance (or culled) gets nothing.
    // These boxes come from our own world pass and follow the mob anywhere it
    // is loaded, through walls.

    fun hasRender(): Boolean = inDungeon && cfg().dungeonHighlightEnabled && cfg().dungeonHighlightBoxes && outlineColors.isNotEmpty()

    fun onWorldRender(matrices: PoseStack, collector: SubmitNodeCollector, camera: Camera) {
        if (!hasRender()) return
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return

        val partial = mc.deltaTracker.getGameTimeDeltaPartialTick(true)
        val cam = camera.position()
        matrices.pushPose()
        matrices.translate(-cam.x, -cam.y, -cam.z)
        for ((id, argb) in outlineColors.entries.toList()) {
            val e = level.getEntity(id) ?: continue
            if (!e.isAlive) continue
            val r = ((argb shr 16) and 0xFF) / 255f; val g = ((argb shr 8) and 0xFF) / 255f; val b = (argb and 0xFF) / 255f
            val lerp = e.getPosition(partial).subtract(e.position())
            val box = e.boundingBox.inflate(0.05).move(lerp)
            val x1 = box.minX.toFloat(); val y1 = box.minY.toFloat(); val z1 = box.minZ.toFloat()
            val x2 = box.maxX.toFloat(); val y2 = box.maxY.toFloat(); val z2 = box.maxZ.toFloat()
            collector.submitCustomGeometry(matrices, FamilyRenderTypes.LINES) { pose, buf -> boxEdges(buf, pose, x1, y1, z1, x2, y2, z2, r, g, b, 1f) }
            collector.submitCustomGeometry(matrices, FamilyRenderTypes.LINES_NO_DEPTH) { pose, buf -> boxEdges(buf, pose, x1, y1, z1, x2, y2, z2, r, g, b, 0.45f) }
        }
        matrices.popPose()
    }

    private fun boxEdges(
        buf: com.mojang.blaze3d.vertex.VertexConsumer, entry: PoseStack.Pose,
        x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float,
        r: Float, g: Float, b: Float, a: Float,
    ) {
        val edges = arrayOf(
            floatArrayOf(x1,y1,z1,x2,y1,z1), floatArrayOf(x2,y1,z1,x2,y1,z2),
            floatArrayOf(x2,y1,z2,x1,y1,z2), floatArrayOf(x1,y1,z2,x1,y1,z1),
            floatArrayOf(x1,y2,z1,x2,y2,z1), floatArrayOf(x2,y2,z1,x2,y2,z2),
            floatArrayOf(x2,y2,z2,x1,y2,z2), floatArrayOf(x1,y2,z2,x1,y2,z1),
            floatArrayOf(x1,y1,z1,x1,y2,z1), floatArrayOf(x2,y1,z1,x2,y2,z1),
            floatArrayOf(x2,y1,z2,x2,y2,z2), floatArrayOf(x1,y1,z2,x1,y2,z2)
        )
        for (ed in edges) {
            val dx = ed[3]-ed[0]; val dy = ed[4]-ed[1]; val dz = ed[5]-ed[2]
            buf.addVertex(entry, ed[0], ed[1], ed[2]).setColor(r, g, b, a).setNormal(entry, dx, dy, dz).setLineWidth(2.0f)
            buf.addVertex(entry, ed[3], ed[4], ed[5]).setColor(r, g, b, a).setNormal(entry, dx, dy, dz).setLineWidth(2.0f)
        }
    }

    /** Find the actual mob a starred nametag armor stand belongs to. */
    private fun resolveMob(stand: ArmorStand): Entity? {
        val level = Minecraft.getInstance().level ?: return null
        val found = level.getEntities(stand as Entity, stand.boundingBox.inflate(0.0, 1.0, 0.0)) { isValidMob(it) }
            .firstOrNull()
        if (found != null) return found
        return level.getEntity(stand.id - 1)?.takeIf { isValidMob(it) }
    }

    private fun isValidMob(entity: Entity): Boolean = when (entity) {
        is ArmorStand -> false
        // Hypixel NPC-mobs use uuid v2; never match real players or ourselves.
        is Player -> entity.uuid.version() == 2 && entity != Minecraft.getInstance().player
        else -> true
    }

    private fun isRealPlayer(entity: Entity): Boolean {
        if (entity !is Player) return false
        val handler = Minecraft.getInstance().connection ?: return false
        return handler.getPlayerInfo(entity.uuid) != null
    }

    /** Parse "chroma:alpha:r:g:b" → opaque ARGB int for the outline shader. */
    private fun parseColor(s: String, fallback: Int): Int {
        return try {
            val p = s.split(":")
            (0xFF shl 24) or (p[2].toInt() shl 16) or (p[3].toInt() shl 8) or p[4].toInt()
        } catch (e: Exception) { fallback }
    }

    /** Called from EntityOutlineMixin every frame per entity. 0 = no outline. */
    fun getOutlineColor(entity: Entity): Int {
        if (!inDungeon || outlineColors.isEmpty()) return 0
        if (!cfg().dungeonHighlightEnabled) return 0
        val color = outlineColors[entity.id] ?: return 0
        if (!entity.isAlive) return 0
        if (!cfg().dungeonHighlightBehindWalls) {
            val player = Minecraft.getInstance().player ?: return 0
            if (!player.hasLineOfSight(entity)) return 0
        }
        return color
    }
}
