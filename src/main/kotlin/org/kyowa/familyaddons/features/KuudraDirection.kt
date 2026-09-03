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
 * Kuudra phase-4 "peek" direction callout: RIGHT / FRONT / LEFT / BACK.
 * Ported from pawsup-1.2.5 Direction.
 *
 * During the peek Kuudra is a giant magma cube (width >= 14.5) whose health
 * sits just under max (24900 < hp <= 25000). Its x/z position against the
 * arena thresholds tells which side he's peeking from.
 *
 * When the player is eaten (teleported to y == 6) the last callout is frozen
 * on screen for 1 second so it stays readable through the swallow.
 */
object KuudraDirection {

    private const val FIGHT_OVER_MSG =
        "[NPC] Elle: POW! SURELY THAT'S IT! I don't think he has any more in him!"
    private const val RUN_START_MSG =
        "[NPC] Elle: Okay adventurers, I will go and fish up Kuudra!"

    const val PREVIEW_TEXT = "FRONT!"

    private var direction: String? = null
    private var directionColor: Int = 0xFFFFFF
    private var lastDirection: String? = null
    private var lastDirectionColor: Int = 0xFFFFFF
    private var showFor: Long = -1L   // keep last callout on screen until this timestamp
    private var skip = false          // set when Elle announces the fight is over

    private fun cfg() = FamilyConfigManager.config.kuudra

    fun getScale() = cfg().directionScale.toFloatOrNull()?.coerceAtLeast(0.5f) ?: 2f

    private fun reset() {
        direction = null
        lastDirection = null
        showFor = -1L
        skip = false
    }

    /** Called from PlayerPositionPacketMixin on every server teleport. */
    fun onTeleport(pos: Vec3) {
        if (!cfg().directionEnabled) return
        // Teleport to y == 6 -> eaten / dropped into the belly: freeze the
        // last direction on screen for 1 second.
        if (pos.y.toInt() == 6) {
            lastDirection = direction
            lastDirectionColor = directionColor
            showFor = System.currentTimeMillis() + 1000L
        }
    }

    fun register() {
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> reset() }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> reset() }

        ClientReceiveMessageEvents.ALLOW_GAME.register { message, _ ->
            val plain = message.string.replace(COLOR_CODE_REGEX, "").trim()
            when (plain) {
                RUN_START_MSG  -> reset()
                FIGHT_OVER_MSG -> skip = true
            }
            true
        }

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (!cfg().directionEnabled) return@register

            if (skip && direction != null) direction = null

            if (!skip && direction == null) {
                // Kuudra during the peek: giant magma cube, width >= 14.5,
                // health just under max (24900 < hp <= 25000).
                val kuudra = client.level?.entitiesForRendering()
                    ?.filterIsInstance<MagmaCube>()
                    ?.firstOrNull { it.bbWidth >= 14.5f && it.health <= 25000f && it.health > 24900f }

                if (kuudra != null) {
                    val x = kuudra.x
                    val z = kuudra.z
                    when {
                        x < -128.0 -> { direction = "RIGHT!"; directionColor = 0xFF5555 }
                        z > -84.0  -> { direction = "FRONT!"; directionColor = 0x00AA00 }
                        x > -72.0  -> { direction = "LEFT!";  directionColor = 0x55FF55 }
                        z < -132.0 -> { direction = "BACK!";  directionColor = 0xAA0000 }
                    }
                }
            } else if (direction != null && kuudraGone(client)) {
                // Peek ended (health moved out of the window) — clear so the
                // next peek can be detected.
                direction = null
            }
        }

        HudElementRegistry.addLast(
            Identifier.fromNamespaceAndPath("familyaddons", "kuudra_direction"),
            HudElement { context, _ ->
                if (!cfg().directionEnabled) return@HudElement

                val (text, color) = when {
                    direction != null -> direction!! to directionColor
                    System.currentTimeMillis() < showFor -> (lastDirection ?: return@HudElement) to lastDirectionColor
                    else -> return@HudElement
                }

                val client = Minecraft.getInstance()
                val tr = client.font
                val scale = getScale()
                val tw = tr.width(text)

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
                context.text(tr, Component.literal(text), 0, 0, (0xFF shl 24) or color, true)
                matrices.popMatrix()
            }
        )
    }

    private fun kuudraGone(client: Minecraft): Boolean {
        val level = client.level ?: return true
        return level.entitiesForRendering()
            .filterIsInstance<MagmaCube>()
            .none { it.bbWidth >= 14.5f && it.health <= 25000f && it.health > 24900f }
    }
}
