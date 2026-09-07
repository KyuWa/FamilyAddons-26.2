package org.kyowa.familyaddons.features

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.monster.cubemob.MagmaCube
import net.minecraft.world.phys.Vec3
import org.kyowa.familyaddons.COLOR_CODE_REGEX
import org.kyowa.familyaddons.config.FamilyConfigManager

/**
 * Kuudra DPS-phase direction callout: RIGHT / FRONT / LEFT / BACK.
 * Ported from pawsup-1.2.5 Direction (same detection as the old JS module).
 *
 * Kuudra is a giant magma cube (width >= 14.5). Hypixel drives his synced
 * health from 100k down to 25k over the stun phases; the moment he surfaces
 * for the DPS phase it sits at exactly 25000 and starts dropping with the
 * first hit. So "24900 < hp <= 25000" means "Kuudra just surfaced", and his
 * x/z against the arena thresholds tells which side he came up on.
 *
 * That window closes on the very first hit, so the callout is NOT tied to
 * it: once a direction is detected it stays on screen for [HOLD_MS] after
 * the last in-window tick (the JS version re-fired a 1.5 s title every tick
 * while in the window, which gave the same effect). If Kuudra surfaces again
 * the direction is re-evaluated and the hold restarts.
 *
 * When the player is eaten (teleported to y == 6) the callout is kept for at
 * least one more second so it stays readable through the swallow.
 */
object KuudraDirection {

    private const val FIGHT_OVER_MSG =
        "[NPC] Elle: POW! SURELY THAT'S IT! I don't think he has any more in him!"
    private const val RUN_START_MSG =
        "[NPC] Elle: Okay adventurers, I will go and fish up Kuudra!"

    /** How long the callout stays visible after Kuudra's HP left the surface window. */
    private const val HOLD_MS = 2000L
    private const val EATEN_HOLD_MS = 1000L

    private const val KUUDRA_MIN_WIDTH = 14.5f
    private const val SURFACE_HP_MAX = 25000f
    private const val SURFACE_HP_MIN = 24900f

    const val PREVIEW_TEXT = "FRONT!"

    @Volatile private var direction: String? = null
    @Volatile private var directionColor: Int = 0xFFFFFF
    @Volatile private var showUntil: Long = -1L   // wall-clock ms; callout visible while now < showUntil
    @Volatile private var skip = false            // set when Elle announces the fight is over

    // Debug bookkeeping for /fa kuudra
    @Volatile private var lastKuudraSeenMs: Long = -1L
    @Volatile private var lastKuudraHp: Float = -1f
    @Volatile private var lastKuudraPos: Vec3? = null
    @Volatile private var lastDetectMs: Long = -1L

    private fun cfg() = FamilyConfigManager.config.kuudra

    /**
     * Scale comes from the config slider; the HUD editor writes the same
     * value back through [directionScale] so both stay in sync.
     */
    fun getScale(): Float = cfg().directionScaleSlider.coerceIn(0.5f, 8f)

    private fun textColor(): Int {
        val c = cfg()
        if (!c.directionCustomColor) return directionColor
        return try {
            val p = c.directionColor.split(":")
            (p[2].toInt() shl 16) or (p[3].toInt() shl 8) or p[4].toInt()
        } catch (e: Exception) { 0xFFFF55 }
    }

    private fun reset() {
        direction = null
        showUntil = -1L
        skip = false
        lastKuudraSeenMs = -1L
        lastKuudraHp = -1f
        lastKuudraPos = null
        lastDetectMs = -1L
    }

    /** Called from PlayerPositionPacketMixin on every server teleport. */
    fun onTeleport(pos: Vec3) {
        if (!cfg().directionEnabled) return
        // Teleport to y == 6 -> eaten / dropped into the belly: keep the
        // current callout on screen for at least another second.
        if (pos.y.toInt() == 6 && direction != null) {
            showUntil = maxOf(showUntil, System.currentTimeMillis() + EATEN_HOLD_MS)
        }
    }

    private fun isKuudra(cube: MagmaCube): Boolean = cube.bbWidth >= KUUDRA_MIN_WIDTH

    private fun isSurfacing(cube: MagmaCube): Boolean =
        cube.health <= SURFACE_HP_MAX && cube.health > SURFACE_HP_MIN

    private fun directionFor(x: Double, z: Double): Pair<String, Int>? = when {
        x < -128.0 -> "RIGHT!" to 0xFF5555
        z > -84.0  -> "FRONT!" to 0x00AA00
        x > -72.0  -> "LEFT!"  to 0x55FF55
        z < -132.0 -> "BACK!"  to 0xAA0000
        else       -> null
    }

    fun register() {
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> reset() }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> reset() }

        ClientReceiveMessageEvents.ALLOW_GAME.register { message, _ ->
            val plain = message.string.replace(COLOR_CODE_REGEX, "").trim()
            when (plain) {
                RUN_START_MSG -> reset()
                // "KUUDRA DOWN!" ends the run. Elle's "POW! SURELY THAT'S IT!"
                // is NOT the end: it fires when the last pod is destroyed, right
                // BEFORE Kuudra surfaces for the DPS phase — i.e. right before
                // the HP window this feature keys on. Treating it as "fight
                // over" (as the original port did) disabled the callout exactly
                // when it was needed.
                "KUUDRA DOWN!" -> skip = true
            }
            true
        }

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (!cfg().directionEnabled) return@register
            // Only Infernal (T5) has the surfacing mechanic this reads.
            if (AutoRequeue.kuudraTierIndex() != 5) return@register

            if (skip) {
                if (direction != null) { direction = null; showUntil = -1L }
                return@register
            }

            val level = client.level ?: return@register
            var kuudra: MagmaCube? = null
            for (entity in level.entitiesForRendering()) {
                if (entity is MagmaCube && isKuudra(entity)) { kuudra = entity; break }
            }
            if (kuudra == null) return@register

            val now = System.currentTimeMillis()
            lastKuudraSeenMs = now
            lastKuudraHp = kuudra.health
            lastKuudraPos = kuudra.position()

            if (!isSurfacing(kuudra)) return@register

            val (text, color) = directionFor(kuudra.x, kuudra.z) ?: return@register
            if ((direction != text || now - lastDetectMs > HOLD_MS) && org.kyowa.familyaddons.util.DevAccess.debug()) {
                org.kyowa.familyaddons.FamilyAddons.LOGGER.info("KuudraDirection: $text (hp=${"%.0f".format(kuudra.health)} @ ${"%.1f".format(kuudra.x)}, ${"%.1f".format(kuudra.z)})")
            }
            direction = text
            directionColor = color
            showUntil = now + HOLD_MS
            lastDetectMs = now
        }

        HudElementRegistry.addLast(
            Identifier.fromNamespaceAndPath("familyaddons", "kuudra_direction"),
            HudElement { context, _ ->
                if (!cfg().directionEnabled) return@HudElement
                val text = direction ?: return@HudElement
                if (System.currentTimeMillis() >= showUntil) return@HudElement
                val color = textColor()

                val client = Minecraft.getInstance()
                val tr = client.font
                val scale = getScale()
                val component = Component.literal(text).withStyle { it.withBold(cfg().directionBold) }
                val tw = tr.width(component)

                val x = if (cfg().directionHudX == -1)
                    ((context.guiWidth() - tw * scale) / 2f).toInt()
                else cfg().directionHudX
                val y = if (cfg().directionHudY == -1)
                    (context.guiHeight() * 0.41f).toInt()
                else cfg().directionHudY

                val matrices = context.pose()
                matrices.pushMatrix()
                matrices.translate(x.toFloat(), y.toFloat())
                matrices.scale(scale, scale)
                context.text(tr, component, 0, 0, (0xFF shl 24) or color, true)
                matrices.popMatrix()
            }
        )
    }

    // ── Debug ──────────────────────────────────────────────────────────

    /** Debug dump for /fa kuudra: current state plus every large magma cube in the world. */
    fun debugDump(): String {
        val sb = StringBuilder()
        val now = System.currentTimeMillis()
        sb.append("§6[FA Direction] §7enabled=").append(if (cfg().directionEnabled) "§atrue" else "§cfalse")
            .append(" §7skip=").append(if (skip) "§ctrue" else "§afalse")
            .append(" §7direction=§e").append(direction ?: "(none)")
            .append(" §7visible=").append(if (direction != null && now < showUntil) "§atrue" else "§cfalse")
            .append("\n")

        sb.append("§7Last Kuudra sighting: ")
        if (lastKuudraSeenMs < 0) {
            sb.append("§c(never)\n")
        } else {
            val p = lastKuudraPos
            sb.append("§e${(now - lastKuudraSeenMs) / 1000.0}s ago §7hp=§e${"%.0f".format(lastKuudraHp)}")
            if (p != null) sb.append(" §7@ §f${"%.1f".format(p.x)}, ${"%.1f".format(p.y)}, ${"%.1f".format(p.z)}")
            sb.append("\n")
        }
        sb.append("§7Last detection: ")
            .append(if (lastDetectMs < 0) "§c(never)" else "§e${(now - lastDetectMs) / 1000.0}s ago")
            .append("\n")

        val level = Minecraft.getInstance().level
        if (level == null) { sb.append("§c No world.\n"); return sb.toString() }
        val cubes = level.entitiesForRendering().filterIsInstance<MagmaCube>()
        sb.append("§7Magma cubes loaded: §e${cubes.size}\n")
        for (cube in cubes) {
            if (cube.bbWidth < 4f) continue   // skip regular small cubes
            val mark = when {
                !isKuudra(cube)   -> "§8[small]"
                isSurfacing(cube) -> "§a[surfacing]"
                else              -> "§e[kuudra]"
            }
            val dir = directionFor(cube.x, cube.z)?.first ?: "?"
            sb.append("  ").append(mark)
                .append(" §7w=§f${"%.1f".format(cube.bbWidth)} §7hp=§f${"%.0f".format(cube.health)}/${"%.0f".format(cube.maxHealth)}")
                .append(" §7@ §f${"%.1f".format(cube.x)}, ${"%.1f".format(cube.y)}, ${"%.1f".format(cube.z)}")
                .append(" §7→ §e").append(dir)
                .append("\n")
        }
        return sb.toString()
    }
}
