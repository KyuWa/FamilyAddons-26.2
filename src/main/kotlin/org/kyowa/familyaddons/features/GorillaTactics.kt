package org.kyowa.familyaddons.features

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.minecraft.client.Minecraft
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionResult
import net.minecraft.world.InteractionHand
import org.kyowa.familyaddons.COLOR_CODE_REGEX
import org.kyowa.familyaddons.FamilyAddons
import org.kyowa.familyaddons.config.FamilyConfigManager
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.resources.Identifier

object GorillaTactics {

    // 3 second Gorilla Tactics teleport window = 60 server ticks
    private const val DURATION_TICKS = 60

    @Volatile private var remainingTicks = -1

    const val PREVIEW_TEXT = "§6Gorilla Tactics §f2.75s"

    fun getScale() = FamilyConfigManager.config.kuudra.gorillaHudScale
        .toFloatOrNull()?.coerceAtLeast(0.5f) ?: 1.5f

    fun resolvePos(sw: Int, sh: Int, scale: Float, textWidth: Int): Pair<Int, Int> {
        val cfg = FamilyConfigManager.config.kuudra
        return if (cfg.gorillaHudX == -1 || cfg.gorillaHudY == -1) {
            val x = ((sw - textWidth * scale) / 2f).toInt()
            val y = (sh / 2f + 40f).toInt()
            x to y
        } else {
            cfg.gorillaHudX to cfg.gorillaHudY
        }
    }

    private fun isGorillaItem(stack: ItemStack): Boolean {
        if (stack.isEmpty) return false

        val customData = stack.get(DataComponents.CUSTOM_DATA)
        if (customData != null) {
            val nbt = customData.copyTag()
            val id = nbt.getString("id").orElse(null)?.ifBlank { null }
                ?: nbt.getCompoundOrEmpty("ExtraAttributes").getString("id").orElse(null)?.ifBlank { null }
            if (id == "TACTICAL_INSERTION") return true
        }

        val sb = StringBuilder()
        sb.append(stack.hoverName.string)
        val lore = stack.get(DataComponents.LORE)
        if (lore != null) {
            for (line in lore.lines) sb.append('\n').append(line.string)
        }
        val text = sb.toString().replace(COLOR_CODE_REGEX, "")
        return text.contains("Gorilla Tactics", ignoreCase = true) &&
                text.contains("RIGHT CLICK", ignoreCase = true)
    }

    fun register() {
        // Decrement once per Hypixel server tick (via ClientboundPingPacket).
        // When the server lags, packets stop arriving and this doesn't fire,
        // so the countdown naturally pauses.
        ServerTickTracker.onTick {
            if (remainingTicks > 0) remainingTicks--
        }

        ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
            ServerTickTracker.reset()
            // Don't clear the timer — Hypixel fires JOIN on every world transition
            // and the ability may still be active. Tick counter is world-agnostic.
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            ServerTickTracker.reset()
            remainingTicks = -1
        }

        // Right-click always starts/restarts the timer, regardless of current state.
        UseItemCallback.EVENT.register { player, _, hand ->
            if (!FamilyConfigManager.config.kuudra.gorillaTacticsTimer) {
                return@register InteractionResult.PASS
            }
            val client = Minecraft.getInstance()
            if (player != client.player) return@register InteractionResult.PASS
            if (hand != InteractionHand.MAIN_HAND) return@register InteractionResult.PASS

            val stack = player.getItemInHand(hand)
            if (isGorillaItem(stack)) {
                remainingTicks = DURATION_TICKS
                FamilyAddons.LOGGER.info("GorillaTactics: timer started")
            }
            InteractionResult.PASS
        }

        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("familyaddons", "hud_gorillatactics_1"), HudElement { context, _ ->
            if (!FamilyConfigManager.config.kuudra.gorillaTacticsTimer) return@HudElement
            val ticks = remainingTicks
            if (ticks <= 0) return@HudElement

            // Smooth display: subtract fractional tick since the last server tick.
            // Freezes during lag (the tracker returns 0) instead of drifting.
            val fractional = ServerTickTracker.fractionalTicksSinceLastTick()
            val displayTicks = (ticks - fractional).coerceAtLeast(0.0)

            val client = Minecraft.getInstance()
            val tr = client.font
            val seconds = displayTicks * 0.05
            val text = "§6Gorilla Tactics §f${"%.2f".format(seconds)}s"
            val scale = getScale()
            val tw = tr.width(text.replace(COLOR_CODE_REGEX, ""))
            val (x, y) = resolvePos(context.guiWidth(), context.guiHeight(), scale, tw)

            val matrices = context.pose()
            matrices.pushMatrix()
            matrices.translate(x.toFloat(), y.toFloat())
            matrices.scale(scale, scale)
            context.text(tr, Component.literal(text), 0, 0, -1, true)
            matrices.popMatrix()
        })
    }
}