package org.kyowa.familyaddons.features

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.Holder
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.effect.MobEffects
import org.kyowa.familyaddons.config.FamilyConfigManager

/**
 * Accessibility: keep blindness and nausea off the local player's screen.
 *
 * Both effects reach the client only through ClientboundUpdateMobEffectPacket,
 * so MobEffectPacketMixin drops the packet for our own entity before the
 * effect is ever applied — no fog fade-in, no screen warp, no potion icon.
 * A per-tick sweep also clears an effect that was already active when the
 * toggle was switched on mid-game.
 */
object NoDebuff {

    private fun cfg() = FamilyConfigManager.config.utilities

    private fun blocked(effect: Holder<MobEffect>): Boolean {
        val c = cfg()
        return (c.noBlindness && effect == MobEffects.BLINDNESS) ||
               (c.noNausea && effect == MobEffects.NAUSEA)
    }

    /** Called from MobEffectPacketMixin: true = swallow the packet. */
    fun shouldBlock(entityId: Int, effect: Holder<MobEffect>): Boolean {
        val player = Minecraft.getInstance().player ?: return false
        if (entityId != player.id) return false
        return blocked(effect)
    }

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            val player = client.player ?: return@register
            val c = cfg()
            if (c.noBlindness && player.hasEffect(MobEffects.BLINDNESS)) player.removeEffect(MobEffects.BLINDNESS)
            if (c.noNausea && player.hasEffect(MobEffects.NAUSEA)) player.removeEffect(MobEffects.NAUSEA)
        }
    }
}
