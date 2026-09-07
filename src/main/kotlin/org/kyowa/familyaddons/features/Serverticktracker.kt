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

    // Wall-clock stamps of the last few seconds of ticks, for the observed
    // tick rate (debug: a healthy Hypixel lobby reads ~20/s; ~40/s would mean
    // we are double-counting the ping packet).
    private const val RATE_WINDOW_MS = 5000L
    private val recent = ArrayDeque<Long>()

    /** Server ticks observed per second over the last 5 s, or -1 before the first tick. */
    fun observedTps(): Double {
        if (!everTicked) return -1.0
        val now = System.currentTimeMillis()
        synchronized(recent) {
            while (recent.isNotEmpty() && now - recent.first() > RATE_WINDOW_MS) recent.removeFirst()
            return recent.size / (RATE_WINDOW_MS / 1000.0)
        }
    }

    /** Driven by the packet mixin. Fires once per Hypixel server tick. */
    // Diagnostic: the first PING_SAMPLE ping ids after a reset, with their
    // spacing, so the packet stream can be understood (measured 22-24 pings/s
    // where 20 real ticks were expected).
    private const val PING_SAMPLE = 60
    private var sampled = 0
    private var prevPingMs = 0L
    private val sample = StringBuilder()

    fun onServerTick(pingId: Int = 0) {
        lastTickWallMs = System.currentTimeMillis()
        everTicked = true
        if (sampled < PING_SAMPLE && org.kyowa.familyaddons.util.DevAccess.debug()) {
            sample.append(pingId).append('@').append(if (prevPingMs == 0L) 0 else lastTickWallMs - prevPingMs).append(' ')
            prevPingMs = lastTickWallMs
            if (++sampled == PING_SAMPLE) {
                org.kyowa.familyaddons.FamilyAddons.LOGGER.info("ServerTickTracker: ping sample (id@msSincePrev): $sample")
                sample.setLength(0)
            }
        }
        synchronized(recent) {
            recent.addLast(lastTickWallMs)
            while (recent.isNotEmpty() && lastTickWallMs - recent.first() > RATE_WINDOW_MS) recent.removeFirst()
        }
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
        sampled = 0
        prevPingMs = 0L
        sample.setLength(0)
    }
}