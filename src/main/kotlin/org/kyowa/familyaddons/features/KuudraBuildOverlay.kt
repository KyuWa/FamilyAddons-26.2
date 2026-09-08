package org.kyowa.familyaddons.features

import com.mojang.blaze3d.vertex.PoseStack
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.phys.Vec3
import org.kyowa.familyaddons.COLOR_CODE_REGEX
import org.kyowa.familyaddons.FamilyAddons
import org.kyowa.familyaddons.config.FamilyConfigManager
import org.kyowa.familyaddons.features.pearl.Place
import org.kyowa.familyaddons.util.DevAccess
import java.util.EnumMap
import kotlin.math.exp

/**
 * Kuudra build overlay: during the build phase (between Elle's "OMG! Great
 * work collecting my supplies!" and "Phew! The Ballista is finally ready!")
 * every supply pile gets a beacon beam whose colour slides from red (0%)
 * through yellow to green (100%) as that pile's build progresses, plus the
 * percentage above it.
 *
 * Progress comes from the pile's own nametag stand: Hypixel names it
 * "PROGRESS: 45%" while building and "PROGRESS: COMPLETE" when done (format
 * confirmed against IQ Addons' BuildWaypointsFeature). The separate
 * "Building Progress: X% (N Players Helping)" stand is the whole-build total
 * and is ignored here. Each stand is matched to the nearest [Place] within
 * [PILE_RADIUS] blocks and the beam is drawn where the stand actually is.
 * The displayed value eases towards the real one over
 * [KuudraConfig.buildFadeSeconds] so the colour change is a glide, not a
 * jump. A pile whose stand left tracking range keeps its last known value
 * until the stand is seen again; progress can fall as well as rise, since
 * mobs break piles down.
 */
object KuudraBuildOverlay {

    private val PROGRESS_REGEX = Regex("""PROGRESS:\s*(\d{1,3})\s*%""")
    private val COMPLETE_REGEX = Regex("""PROGRESS:\s*COMPLETE""")

    private const val PILE_RADIUS_SQ = 8.0 * 8.0
    private const val SCAN_INTERVAL_TICKS = 4
    private const val BEAM_BASE_Y = 79.0
    private const val BEAM_HEIGHT = 80.0

    /** Real progress per pile, 0..100, as last read off its stand. */
    private val target = EnumMap<Place, Int>(Place::class.java)
    /** Where the pile's PROGRESS stand actually is (beam base), per pile. */
    private val standPos = EnumMap<Place, Vec3>(Place::class.java)
    /** True while the stand reads "PROGRESS: COMPLETE" (a 100% that is not complete exists too). */
    private val complete = EnumMap<Place, Boolean>(Place::class.java)
    /** Eased progress per pile as currently drawn. */
    private val shown = EnumMap<Place, Float>(Place::class.java)
    private var lastFrameNanos = 0L
    private var scanTicker = 0
    /** Wall time when all six piles were first seen COMPLETE at once; 0 = not all complete. */
    private var allCompleteSinceMs = 0L
    /** Stand names seen near piles this run (debug: confirms the nametag format). */
    private val seenNames = LinkedHashSet<String>()

    private fun cfg() = FamilyConfigManager.config.kuudra

    private fun reset() {
        target.clear()
        standPos.clear()
        complete.clear()
        allCompleteSinceMs = 0L
        shown.clear()
        seenNames.clear()
        lastFrameNanos = 0L
    }

    fun register() {
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> reset() }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> reset() }

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (!KuudraPhase.isInBuild() || !AutoRequeue.isInKuudra()) {
                if (target.isNotEmpty() || shown.isNotEmpty()) reset()
                return@register
            }
            if (!cfg().buildOverlayEnabled) return@register
            if (++scanTicker < SCAN_INTERVAL_TICKS) return@register
            scanTicker = 0
            scan(client)
        }
    }

    private fun scan(client: Minecraft) {
        val world = client.level ?: return
        for (entity in world.entitiesForRendering()) {
            if (entity !is ArmorStand || !entity.isAlive) continue
            val raw = entity.customName ?: continue
            val name = raw.string.replace(COLOR_CODE_REGEX, "").trim()
            if (!name.startsWith("PROGRESS:")) continue // skips "Building Progress: X%" (whole-build total)
            val isComplete = COMPLETE_REGEX.containsMatchIn(name)
            val pct = when {
                isComplete -> 100
                else -> PROGRESS_REGEX.find(name)?.groupValues?.get(1)?.toInt()?.coerceIn(0, 100) ?: continue
            }
            val place = nearestPile(entity.x, entity.z)
            if (DevAccess.debug() && seenNames.add(name)) {
                FamilyAddons.LOGGER.info("KuudraBuildOverlay: '$name' at ${"%.1f".format(entity.x)}, ${"%.1f".format(entity.y)}, ${"%.1f".format(entity.z)} -> ${place?.name ?: "no pile within 8"}")
            }
            if (place == null) continue
            standPos[place] = Vec3(entity.x, entity.y, entity.z)
            complete[place] = isComplete
            // Not sticky-max: mobs can knock a pile back down, even a completed one,
            // so the last value the stand showed is the truth.
            target[place] = pct
        }
        val allDone = Place.values().all { complete[it] == true }
        allCompleteSinceMs = when {
            !allDone -> 0L
            allCompleteSinceMs == 0L -> System.currentTimeMillis()
            else -> allCompleteSinceMs
        }
    }

    /**
     * The build is over once every pile has read COMPLETE for the grace period
     * (Elle's "Ballista is finally ready" line ends the phase too, but this does
     * not wait for it). A pile knocked back down un-completes and the overlay
     * returns.
     */
    private fun finishedByPiles(): Boolean {
        val c = cfg()
        if (!c.buildHideWhenComplete) return false
        val since = allCompleteSinceMs
        if (since == 0L) return false
        return System.currentTimeMillis() - since >= (c.buildCompleteDelay * 1000f).toLong()
    }

    /**
     * Mixin hook: hide Hypixel's own PROGRESS nametag on a build pile stand while
     * the overlay is drawing its replacement.
     */
    fun shouldHideNametag(entity: Entity): Boolean {
        if (entity !is ArmorStand) return false
        val c = cfg()
        if (!c.buildOverlayEnabled || !c.buildHideHypixelText || !c.buildShowPercent) return false
        if (!KuudraPhase.isInBuild() || !AutoRequeue.isInKuudra()) return false
        if (finishedByPiles()) return false
        val name = (entity.customName ?: return false).string.replace(COLOR_CODE_REGEX, "").trim()
        return name.startsWith("PROGRESS:")
    }

    private fun nearestPile(x: Double, z: Double): Place? {
        var best: Place? = null
        var bestD = PILE_RADIUS_SQ
        for (place in Place.values()) {
            val dx = x - place.location.x
            val dz = z - place.location.z
            val d = dx * dx + dz * dz
            if (d < bestD) { bestD = d; best = place }
        }
        return best
    }

    // ── Render ─────────────────────────────────────────────────────────

    fun hasRender(): Boolean {
        if (!cfg().buildOverlayEnabled) return false
        if (!KuudraPhase.isInBuild() || !AutoRequeue.isInKuudra()) return false
        if (finishedByPiles()) return false
        return Minecraft.getInstance().player != null
    }

    fun onWorldRender(matrices: PoseStack, collector: SubmitNodeCollector, camera: Camera) {
        if (!hasRender()) return
        val c = cfg()
        val mc = Minecraft.getInstance()

        // Ease the drawn value towards the real one: exponential glide with the
        // configured time constant, frame-rate independent.
        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 0f else ((now - lastFrameNanos) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.25f)
        lastFrameNanos = now
        val tau = c.buildFadeSeconds.coerceIn(0.05f, 5f)
        val k = 1f - exp(-dt / tau)

        val cam = camera.position()
        matrices.pushPose()
        matrices.translate(-cam.x, -cam.y, -cam.z)

        for (place in Place.values()) {
            val real = target[place] ?: continue
            val cur = shown[place] ?: (if (dt == 0f) real.toFloat() else 0f)
            val next = cur + (real - cur) * k
            shown[place] = next
            val t = (next / 100f).coerceIn(0f, 1f)

            if (c.buildHideFinished && real >= 100 && t > 0.995f) continue

            val at = standPos[place] ?: place.location
            val (r, g, b) = rampColor(t)
            BeaconBeamRenderer.drawBeam(
                matrices, collector,
                at.x, BEAM_BASE_Y, at.z,
                BEAM_HEIGHT,
                r, g, b,
                c.buildBeamOpacity,
                c.buildBeamWidth,
            )
            if (c.buildShowPercent) {
                // The text shows what the stand really says (jumps 0 → 15 → 40 like
                // Hypixel's), only the beam colour glides.
                val done = complete[place] == true
                val code = when { done -> "§2"; real >= 50 -> "§e"; else -> "§c" }
                val text = "$code§lPROGRESS: " + (if (done) "COMPLETE" else "$real%")
                val pos = Vec3(at.x, at.y + 1.6, at.z)
                PearlWaypoints.drawLabel(matrices, collector, pos, text, c.buildPercentScale, 2)
            }
        }

        matrices.popPose()
    }

    /** 0 → red, 0.5 → amber, 1 → dark green; linear between the keys. */
    private fun rampColor(t: Float): Triple<Float, Float, Float> {
        val red = floatArrayOf(0.85f, 0.05f, 0.05f)
        val amber = floatArrayOf(1f, 0.75f, 0f)
        val green = floatArrayOf(0.05f, 0.5f, 0.1f)
        val (a, b, u) = if (t < 0.5f) Triple(red, amber, t * 2f) else Triple(amber, green, (t - 0.5f) * 2f)
        return Triple(a[0] + (b[0] - a[0]) * u, a[1] + (b[1] - a[1]) * u, a[2] + (b[2] - a[2]) * u)
    }

    // ── Debug ──────────────────────────────────────────────────────────

    fun debugDump(): String {
        val sb = StringBuilder()
        sb.append("§6[FA Build] §7phase=").append(if (KuudraPhase.isInBuild()) "§atrue" else "§cfalse")
            .append(" §7enabled=").append(if (cfg().buildOverlayEnabled) "§atrue" else "§cfalse")
            .append(" §7piles=§e").append(target.size)
            .append(" §7allComplete=").append(if (allCompleteSinceMs != 0L) "§a${(System.currentTimeMillis() - allCompleteSinceMs) / 1000}s" else "§cno")
            .append(" §7finished=").append(if (finishedByPiles()) "§atrue" else "§cfalse").append("\n")
        for ((place, pct) in target) {
            val at = standPos[place]
            sb.append("  §7${place.name} §f$pct%${if (complete[place] == true) " COMPLETE" else ""} §8(shown ${"%.0f".format(shown[place] ?: 0f)}")
            if (at != null) sb.append(" @ ${"%.1f".format(at.x)}, ${"%.1f".format(at.y)}, ${"%.1f".format(at.z)}")
            sb.append(")\n")
        }
        if (seenNames.isNotEmpty()) sb.append("  §7names: §f").append(seenNames.joinToString(" | ")).append("\n")
        return sb.toString()
    }
}
