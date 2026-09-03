package org.kyowa.familyaddons.features

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Server-tick signal driven by ClientboundPingPacket (negative-parameter variant),
 * which Hypixel sends every single server tick at 20Hz.
 *
 * This is the same mechanism Odin and Devonian use for their tick timers. Unlike
 * WorldTimeUpdateS2CPacket (which fires ~once/second), ping packets fire every
 * server tick and correspond exactly to server-side time. When the server lags,
 * ping packets stop arriving and listeners stop being invoked — countdowns
 * using this signal naturally pause.
 *
 * Driven by ServerTickPacketMixin -> onServerTick().
 */
object ServerTickTracker {

    private const val MS_PER_TICK = 50L
    private const val LAG_THRESHOLD_MS = 1500L

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile private var lastTickWallMs: Long = 0L
    @Volatile private var everTicked: Boolean = false

    /** Register a callback invoked once per observed server tick. */
    fun onTick(listener: () -> Unit) {
        listeners.add(listener)
    }

    /** Driven by the packet mixin. Fires once per Hypixel server tick. */
    fun onServerTick() {
        lastTickWallMs = System.currentTimeMillis()
        everTicked = true
        for (l in listeners) {
            try { l() } catch (_: Throwable) { /* don't let one bad listener break others */ }
        }
    }

    /**
     * Fractional tick elapsed since the last server-tick packet arrived, for smooth
     * HUD display between whole-tick decrements. Capped so it freezes during lag
     * instead of drifting ahead of the true remaining time.
     */
    fun fractionalTicksSinceLastTick(): Double {
        if (!everTicked) return 0.0
        val elapsedMs = System.currentTimeMillis() - lastTickWallMs
        if (elapsedMs >= LAG_THRESHOLD_MS) return 0.0
        // Clamp to just under 1 tick; if we're past a tick the next packet is just late.
        return (elapsedMs / MS_PER_TICK.toDouble()).coerceAtMost(0.99)
    }

    fun reset() {
        everTicked = false
        lastTickWallMs = 0L
    }
}