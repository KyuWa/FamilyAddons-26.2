package org.kyowa.familyaddons.features

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.rendertype.RenderType
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import org.kyowa.familyaddons.COLOR_CODE_REGEX
import org.kyowa.familyaddons.config.FamilyConfigManager

object EntityHighlight {

    val highlighted = mutableSetOf<Entity>()          // Highlight-category matches
    val bestiaryHighlighted = mutableSetOf<Entity>()  // Bestiary zone/mob matches
    private var tick = 0

    /** True when any bestiary highlight source is configured (master toggle
     *  is checked separately — the category's Enable Highlight gates all). */
    private fun bestiaryActive(): Boolean {
        val cfg = FamilyConfigManager.config.highlight
        if (!cfg.enabled) return false
        if (cfg.zoneHighlightEnabled && cfg.bestiaryZone != 0) return true
        return cfg.mobName.isNotBlank()
    }

    private fun shouldScan(): Boolean {
        val cfg = FamilyConfigManager.config.highlight
        if (!cfg.enabled) return false
        if (cfg.mobNames.isNotBlank()) return true
        if (cfg.zoneHighlightEnabled && cfg.bestiaryZone != 0) return true
        return cfg.mobName.isNotBlank()
    }

    /** Highlight name-list match: loose `.contains()` on name or customName. */
    private fun matchesManual(entity: Entity): Boolean {
        if (!FamilyConfigManager.config.highlight.enabled) return false
        val manualNames = FamilyConfigManager.config.highlight.mobNames
            .split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
        if (manualNames.isEmpty()) return false
        val name = entity.name.string.replace(COLOR_CODE_REGEX, "").lowercase()
        val customNameRaw = entity.customName?.string?.replace(COLOR_CODE_REGEX, "")?.lowercase()
        return manualNames.any { n -> name.contains(n) || customNameRaw?.contains(n) == true }
    }

    /**
     * Bestiary match: the single tracked mob (loose contains, legacy
     * behaviour), plus the zone-highlight names (stripped customName, exact
     * equality or one allowed modifier prefix — see [matchesWithModifier]).
     */
    private fun matchesBestiary(entity: Entity): Boolean {
        val bestiary = FamilyConfigManager.config.highlight
        val name = entity.name.string.replace(COLOR_CODE_REGEX, "").lowercase()
        val customNameRaw = entity.customName?.string?.replace(COLOR_CODE_REGEX, "")?.lowercase()

        val tracked = bestiary.mobName.trim().lowercase()
        if (tracked.isNotBlank() && (name.contains(tracked) || customNameRaw?.contains(tracked) == true)) {
            return true
        }

        if (bestiary.zoneHighlightEnabled && customNameRaw != null) {
            val zoneNames = BestiaryZoneHighlight.activeMobNames
                .map { it.lowercase() }.filter { it.isNotBlank() }
            if (zoneNames.isNotEmpty()) {
                val stripped = stripBestiaryNametag(customNameRaw)
                if (stripped.isNotBlank() && zoneNames.any { matchesWithModifier(stripped, it) }) return true
            }
        }
        return false
    }

    /**
     * Allowed modifier-word prefixes that share a bestiary entry with the base mob.
     * Per Hypixel wiki, "corrupted" and "runic" are universal spawn variants — a
     * Corrupted Wither Skeleton kill counts toward the Wither Skeleton bestiary.
     * This is intentionally a small whitelist to avoid false positives like
     * "Cave Spider" matching when "Spider" is the active target.
     */
    private val ALLOWED_MODIFIERS = setOf("corrupted", "runic", "sparkling")

    private fun matchesWithModifier(stripped: String, target: String): Boolean {
        if (stripped == target) return true
        // "corrupted wither skeleton" matches target "wither skeleton" iff the
        // text before the target is exactly one allowed modifier word.
        if (stripped.endsWith(" $target")) {
            val prefix = stripped.removeSuffix(" $target")
            if (prefix in ALLOWED_MODIFIERS) return true
        }
        return false
    }

    /**
     * Reduce a Hypixel mob nametag down to just its display name.
     *
     * Strategy: rather than enumerate every prefix/suffix Hypixel uses (level
     * brackets, stars, hearts, runic glyphs, festival markers, mayor perks, etc.),
     * we keep only "name tokens" — whitespace-separated chunks made entirely of
     * letters, apostrophes, or hyphens. Anything containing a digit, bracket,
     * heart, star, or unknown symbol is decoration and gets discarded.
     *
     * Future-proof: when Hypixel adds a new symbol, it gets auto-stripped instead
     * of silently breaking matches.
     *
     * Examples (input → output):
     *   "✯ wither spectre 500❤"           → "wither spectre"
     *   "[lv50] zombie 1,234/5,000❤"      → "zombie"
     *   "wither skeleton 50❤"              → "wither skeleton"
     *   "᠅ runic ghoul ⓢ 2.5m❤"            → "runic ghoul"
     *   "[lv1] flaming spider 100❤"       → "flaming spider"
     *   "✯ bal 7m❤"                        → "bal"
     *
     * NOTE: input is already lowercased and color-code-stripped by the caller.
     */
    private fun stripBestiaryNametag(s: String): String {
        val nameTokenRegex = Regex("""^[a-z'\-]+$""")
        return s.split(Regex("""\s+"""))
            .filter { it.isNotEmpty() && nameTokenRegex.matches(it) }
            .joinToString(" ")
            .trim()
    }

    /**
     * True if [entity] represents a real connected player and must NEVER be highlighted.
     *
     * On Hypixel SkyBlock, mob NPCs are spawned as Player instances (full player skins,
     * custom AI). A real player can be told apart from an NPC because real players have an
     * entry in the tab list (PlayerListEntry); NPC mobs do not. This is the same check used
     * by SkyHanni and Odin to avoid hitting NPCs with anti-cheat-style filters.
     *
     * Returns false for non-player entities (mobs, animals, armor stands etc.).
     */
    private fun isRealPlayer(entity: Entity): Boolean {
        if (entity !is Player) return false
        val handler = Minecraft.getInstance().connection ?: return false
        return handler.getPlayerInfo(entity.uuid) != null
    }

    private fun resolveEntity(entity: Entity): Entity? {
        if (entity is ArmorStand && entity.isInvisible) {
            val world = Minecraft.getInstance().level ?: return null
            val byId = world.getEntity(entity.id - 1)
            // Reject the id-1 candidate if it's any real connected player (not just self).
            if (byId != null && byId !is ArmorStand && !isRealPlayer(byId) && byId.isAlive) return byId
            val candidates = world.getEntitiesOfClass(
                LivingEntity::class.java, entity.boundingBox.inflate(0.5, 1.5, 0.5)
            ) { it !is ArmorStand && !isRealPlayer(it) && it.isAlive }
            return candidates.minByOrNull { val dx = it.x - entity.x; val dz = it.z - entity.z; dx*dx + dz*dz }
        }
        return entity
    }

    fun getOutlineColor(entity: Entity): Int {
        val cfg = FamilyConfigManager.config.highlight
        if (!cfg.enabled) return 0
        // Sparkling overrides the normal colors for both styles.
        val sparklingSet = SparklingCritterHighlight.trackedEntities()
        if (cfg.drawingStyle == 1 && entity in highlighted) {
            return parseOutlineColor(if (entity in sparklingSet) cfg.sparklingColor else cfg.color)
        }
        if (bestiaryActive() && cfg.bestiaryDrawingStyle == 1 && entity in bestiaryHighlighted) {
            return parseOutlineColor(if (entity in sparklingSet) cfg.sparklingColor else cfg.bestiaryColor)
        }
        return 0
    }

    private fun parseOutlineColor(s: String): Int = try {
        val parts = s.split(":")
        (0xFF shl 24) or (parts[2].toInt() shl 16) or (parts[3].toInt() shl 8) or parts[4].toInt()
    } catch (e: Exception) { 0xFFFF0000.toInt() }

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { _ ->
            if (!shouldScan()) {
                if (highlighted.isNotEmpty()) highlighted.clear()
                if (bestiaryHighlighted.isNotEmpty()) bestiaryHighlighted.clear()
                return@register
            }
            val interval = FamilyConfigManager.config.utilities.highlightRescanInterval.toInt().coerceIn(1, 20)
            if (tick++ % interval != 0) return@register
            rescan()
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            highlighted.clear()
            bestiaryHighlighted.clear()
        }
    }

    fun rescan() {
        highlighted.clear()
        bestiaryHighlighted.clear()
        val world = Minecraft.getInstance().level ?: return
        if (!shouldScan()) return
        world.entitiesForRendering().forEach { entity ->
            if (!entity.isAlive) return@forEach
            // Skip any real connected player up-front. This prevents matches like the search
            // term "dragon" highlighting a player named "dragonslayer213". NPC mobs that use
            // player skins (Hypixel's fake-player NPCs) pass this check because they are not
            // in the tab list — they will still be highlighted normally.
            if (isRealPlayer(entity)) return@forEach
            val manual = matchesManual(entity)
            val bestiary = matchesBestiary(entity)
            if (manual || bestiary) {
                // FIX: if resolveEntity returns null (nametag stand can't find its real mob
                // because the mob died this tick), skip entirely. The old `?: entity` fallback
                // would add the armor stand itself to `highlighted`, causing the tracer to
                // briefly snap to the stand's position before it despawns — visible flicker.
                val target = resolveEntity(entity) ?: return@forEach
                // Defensive: never highlight an invisible nametag stand directly.
                if (target is ArmorStand && target.isInvisible) return@forEach
                // Defensive: resolveEntity already filters real players, but double-check.
                if (isRealPlayer(target)) return@forEach
                if (!target.isAlive) return@forEach
                if (manual) highlighted.add(target)
                if (bestiary) bestiaryHighlighted.add(target)
            }
        }
    }

    fun onWorldRender(matrices: PoseStack, collector: SubmitNodeCollector, cam: Vec3) {
        val config = FamilyConfigManager.config.highlight
        if (!config.enabled) return
        val shulkerTargets = ShulkerBoxHighlight.trackedEntities() + SparklingCritterHighlight.trackedEntities()
        val sparklingSet = SparklingCritterHighlight.trackedEntities().toSet()
        if (highlighted.isEmpty() && bestiaryHighlighted.isEmpty() && shulkerTargets.isEmpty()) return

        fun parseRgb(s: String, fallback: Triple<Float, Float, Float>): Triple<Float, Float, Float> = try {
            val parts = s.split(":")
            Triple(parts[2].toInt() / 255f, parts[3].toInt() / 255f, parts[4].toInt() / 255f)
        } catch (e: Exception) { fallback }

        val (r, g, b) = parseRgb(config.color, Triple(1f, 0f, 0f))

        highlighted.removeIf { !it.isAlive }
        bestiaryHighlighted.removeIf { !it.isAlive }

        // ── ESP boxes — each source drawn with its own color/style ────
        fun drawBoxes(targets: Set<Entity>, rgb: Triple<Float, Float, Float>) {
            val (br, bg, bb2) = rgb
            fun drawAll(alpha: Float, renderType: RenderType) {
                collector.submitCustomGeometry(matrices, renderType) { entry, buf ->
                    for (entity in targets) {
                        if (!entity.isAlive) continue
                        val bb = entity.boundingBox
                        drawBoxEdges(buf, entry,
                            (bb.minX - cam.x).toFloat(), (bb.minY - cam.y).toFloat(), (bb.minZ - cam.z).toFloat(),
                            (bb.maxX - cam.x).toFloat(), (bb.maxY - cam.y).toFloat(), (bb.maxZ - cam.z).toFloat(),
                            br, bg, bb2, alpha)
                    }
                }
            }
            drawAll(1.0f, FamilyRenderTypes.LINES)
            drawAll(1.0f, FamilyRenderTypes.LINES_NO_DEPTH)
        }

        if (config.drawingStyle == 0 && highlighted.isNotEmpty()) {
            drawBoxes(highlighted - sparklingSet, Triple(r, g, b))
        }
        if (bestiaryActive() && config.bestiaryDrawingStyle == 0 && bestiaryHighlighted.isNotEmpty()) {
            drawBoxes(bestiaryHighlighted - sparklingSet, parseRgb(config.bestiaryColor, Triple(1f, 0.67f, 0f)))
        }

        // ── Tracer lines ──────────────────────────────────────────────
        // Start offset 0.5 blocks forward from camera to avoid near-plane clipping.
        // That point is directly in front of the camera → projects to crosshair.
        // Uses LINES_NO_DEPTH so the tracer always draws on top of world geometry.
        if (config.tracerEnabled) {
            val count = config.tracerCount.toInt().coerceIn(1, 20)
            val maxBlocks = config.tracerChunkRange.toDouble() * 16.0
            val maxDistSq = maxBlocks * maxBlocks

            // Pick the closest N live+highlighted+in-range mobs each frame.
            // When a mob dies it leaves `highlighted` → instantly drops from this list.
            val targets = ArrayList<Entity>()
            for (entity in highlighted + bestiaryHighlighted + shulkerTargets) {
                if (!entity.isAlive) continue
                // FIX: never run a tracer to an invisible nametag armor stand. Belt-and-braces
                // in case one ever makes it into `highlighted` through some other code path.
                if (entity is ArmorStand) continue
                val dx = entity.x - cam.x
                val dy = (entity.boundingBox.minY + entity.boundingBox.maxY) / 2.0 - cam.y
                val dz = entity.z - cam.z
                if (dx * dx + dy * dy + dz * dz <= maxDistSq) targets.add(entity)
            }
            targets.sortBy { entity ->
                val dx = entity.x - cam.x
                val dy = (entity.boundingBox.minY + entity.boundingBox.maxY) / 2.0 - cam.y
                val dz = entity.z - cam.z
                dx * dx + dy * dy + dz * dz
            }
            val picked = if (targets.size > count) targets.subList(0, count) else targets

            if (picked.isNotEmpty()) {
                val camera = Minecraft.getInstance().gameRenderer.mainCamera()
                val yawRad = Math.toRadians(camera.yRot().toDouble())
                val pitchRad = Math.toRadians(camera.xRot().toDouble())
                val fwdX = -Math.sin(yawRad) * Math.cos(pitchRad)
                val fwdY = -Math.sin(pitchRad)
                val fwdZ = Math.cos(yawRad) * Math.cos(pitchRad)

                val startOffset = 0.5
                val sx = (fwdX * startOffset).toFloat()
                val sy = (fwdY * startOffset).toFloat()
                val sz = (fwdZ * startOffset).toFloat()

                collector.submitCustomGeometry(matrices, FamilyRenderTypes.LINES_NO_DEPTH) { entry, buf ->
                    for (entity in picked) {
                        val ex = (entity.x - cam.x).toFloat()
                        val ey = ((entity.boundingBox.minY + entity.boundingBox.maxY) / 2.0 - cam.y).toFloat()
                        val ez = (entity.z - cam.z).toFloat()

                        val dx = ex - sx; val dy = ey - sy; val dz = ez - sz
                        val len = Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
                        val nx = if (len > 0f) dx / len else 0f
                        val ny = if (len > 0f) dy / len else 0f
                        val nz = if (len > 0f) dz / len else 0f

                        buf.addVertex(entry, sx, sy, sz)
                            .setColor(r, g, b, 1.0f)
                            .setNormal(entry, nx, ny, nz)
                            .setLineWidth(2.0f)
                        buf.addVertex(entry, ex, ey, ez)
                            .setColor(r, g, b, 1.0f)
                            .setNormal(entry, nx, ny, nz)
                            .setLineWidth(2.0f)
                    }
                }
            }
        }
    }

    fun hasHighlighted() = (highlighted.isNotEmpty() || bestiaryHighlighted.isNotEmpty()) && shouldScan()

    internal fun drawBoxEdges(
        buf: VertexConsumer,
        entry: PoseStack.Pose,
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