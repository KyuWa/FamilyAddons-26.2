package org.kyowa.familyaddons.features.pearl

import net.minecraft.world.phys.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.expm1
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pearl trajectory solver.
 *
 * Algorithm: pitch bisection (50 iterations) over a fixed pitch range,
 * with `velocityGivenTime` minimization as a fallback when bisection
 * doesn't converge.
 *
 * Constants extracted from bytecode `ConstantValue` attributes:
 *   THROW_SPEED  = 1.5      — initial pearl velocity magnitude
 *   DRAG         = 0.01     — per-tick drag coefficient
 *   acceleration = (0, -0.03, 0) — gravity per tick
 *   MS_PER_TICK  = 50.0
 *   HIGH_DIST    = 50.0     — aim-point projection distance for high arc
 *   LOW_DIST     = 12.0     — aim-point projection distance for low arc
 *
 * Pitch convention : positive pitch = upward. This is the OPPOSITE
 * of Minecraft's pitch (where positive = looking down). The `lookDir`
 * computed at the end uses `(-sin(yaw)*cos(pitch), -sin(pitch), cos(yaw)*cos(pitch))`
 * so that lookDir.y is negative when  pitch is positive (upward),
 * matching MC's "y points up" convention.
 */
object PearlCalculator {

    private const val THROW_SPEED = 1.5
    private const val DRAG = 0.01
    private const val MS_PER_TICK = 50.0
    private const val HIGH_DIST = 50.0
    private const val LOW_DIST = 12.0

    private val acceleration = Vec3(0.0, -0.03, 0.0)
    private val squaredSpeed = THROW_SPEED * THROW_SPEED  // 2.25

    private val PHI = (1.0 + sqrt(5.0)) / 2.0
    private val invPhi = 1.0 / PHI

    data class PearlSolution(
        val aimPoint: Vec3,
        val flightTimeMs: Long,
        val lookYawDeg: Double,
        val lookPitchDeg: Double,
    )

    /**
     * `velocityGivenTime(time, displacement)`:
     *   v0 = ((displacement * DRAG) - (acceleration * time)) / (1 - exp(-DRAG * time))
     *      + acceleration / DRAG
     *
     * Inverse of the pearl flight equation: given that the pearl reaches
     * `displacement` after `time` ticks, what initial velocity must it have had?
     */
    private fun velocityGivenTime(time: Double, displacement: Vec3): Vec3 {
        // (displacement * DRAG) - (acceleration * time)
        val numer = displacement.scale(DRAG).subtract(acceleration.scale(time))
        // 1 - exp(-DRAG * time)  ==  -expm1(-DRAG * time)
        val divisor = -expm1(-DRAG * time)
        // Avoid divide-by-zero for time→0
        val first = if (abs(divisor) < 1e-12) numer else numer.scale(1.0 / divisor)
        // acceleration / DRAG = acceleration * 100 (terminal velocity vector)
        val second = acceleration.scale(1.0 / DRAG)
        return first.add(second)
    }

    /**
     * Bounded golden-section minimisation. Used as the fallback when pitch
     * bisection doesn't converge.
     */
    private fun minimizeScalarBounded(
        f: (Double) -> Double,
        aIn: Double,
        bIn: Double,
        tol: Double = 1e-6,
        maxIter: Int = 24,
    ): Double {
        var a = aIn
        var b = bIn
        var c = b - (b - a) * invPhi
        var d = a + (b - a) * invPhi
        var fc = f(c)
        var fd = f(d)
        var iter = 0
        while (abs(c - d) > tol && iter < maxIter) {
            if (fc < fd) {
                b = d
                d = c
                fd = fc
                c = b - (b - a) * invPhi
                fc = f(c)
            } else {
                a = c
                c = d
                fc = fd
                d = a + (b - a) * invPhi
                fd = f(d)
            }
            iter++
        }
        return (a + b) * 0.5
    }

    // ── Exact per-tick simulation ──────────────────────────────────────
    // Hypixel simulates the pearl like 1.8 EntityThrowable, per server tick:
    //   pos += v ; v *= 0.99 ; v.y -= 0.03      (speed 1.5, spawn eye - 0.1)
    // The old closed-form pitch search ignored horizontal drag when it timed
    // the throw, which lands the real pearl short: 0.6 blocks at 12 blocks,
    // 2-3 blocks at 20-25 (checked against this simulation 2026-09-07). So
    // the pitch is now found by bisection on the simulated trajectory itself:
    // for a given pitch, march the pearl until it crosses the target's
    // horizontal distance and compare its height there with the target's.
    private const val MAX_TICKS = 300

    /** Height (relative to spawn) and fractional tick when the pearl crosses [horizontalDist], or null if it never gets there. */
    private fun heightAtDistance(pitchDeg: Double, horizontalDist: Double): Pair<Double, Double>? {
        val p = Math.toRadians(pitchDeg)
        var vh = THROW_SPEED * cos(p)
        var vy = THROW_SPEED * sin(p)
        var x = 0.0; var y = 0.0
        var prevX = 0.0; var prevY = 0.0
        for (t in 1..MAX_TICKS) {
            x += vh; y += vy
            vh *= 0.99; vy = vy * 0.99 - 0.03
            if (x >= horizontalDist) {
                val f = if (x != prevX) (horizontalDist - prevX) / (x - prevX) else 1.0
                return Pair(prevY + f * (y - prevY), (t - 1) + f)
            }
            if (y < -400.0) return null
            prevX = x; prevY = y
        }
        return null
    }

    /**
     * Returns Pair(lookDirectionUnitVector, flightTimeTicks).
     *
     * Low arc: pitch in [-89, 45] — the height reached at the target distance
     * rises monotonically with pitch, bisect for height == dy.
     * High arc: pitch in [45, 89] — height falls with pitch (the pearl runs
     * out of range), bisect the other way. A pitch that never reaches the
     * target counts as "too low".
     */
    private fun findLookDirAndTime(
        pos: Vec3,
        target: Vec3,
        highArc: Boolean,
    ): Pair<Vec3, Double> {
        val displacement = target.subtract(pos)
        val dy = displacement.y
        val horizontalDist = sqrt(displacement.x * displacement.x + displacement.z * displacement.z)

        var lo = if (highArc) 45.0 else -89.0
        var hi = if (highArc) 89.0 else 45.0
        var bestPitch = (lo + hi) / 2.0
        var bestTime = 0.0
        var bestErr = Double.MAX_VALUE

        repeat(40) {
            val mid = (lo + hi) / 2.0
            val hit = heightAtDistance(mid, horizontalDist)
            val h = hit?.first ?: -1e9
            val err = abs(h - dy)
            if (hit != null && err < bestErr) { bestErr = err; bestPitch = mid; bestTime = hit.second }
            val tooHigh = h >= dy
            if (highArc) {
                // Steeper = shorter = lower at the target distance.
                if (tooHigh) lo = mid else hi = mid
            } else {
                if (tooHigh) hi = mid else lo = mid
            }
        }

        // Physical launch direction: horizontal unit vector towards the
        // target, tilted up by the solved pitch (y up = positive). The
        // formula the old never-converging branch carried flipped y and
        // mirrored x, which is why 20:12's build aimed at the wrong spot.
        val pitchRad = Math.toRadians(bestPitch)
        val hx = if (horizontalDist > 1e-9) displacement.x / horizontalDist else 0.0
        val hz = if (horizontalDist > 1e-9) displacement.z / horizontalDist else 1.0
        val lookDir = Vec3(
            hx * cos(pitchRad),
            sin(pitchRad),
            hz * cos(pitchRad),
        ).normalize()
        return Pair(lookDir, bestTime)
    }

    /**
     * Top-level solver. Returns null when target is essentially at the
     * spawn position (horizontal distance ≤ 1 block).
     *
     * @param highArc  true = use mortar trajectory
     * @param eyePos   player eye position (used to project the aim point)
     * @param spawnPos pearl spawn position (used for trajectory math; usually
     *                 eye + a small forward offset, but  passes spawnPos
     *                 explicitly so callers control it)
     * @param dest     target world position
     */
    fun solvePearl(
        highArc: Boolean,
        eyePos: Vec3,
        spawnPos: Vec3,
        dest: Vec3,
    ): PearlSolution? {
        val horizontalDist = hypot(dest.x - spawnPos.x, dest.z - spawnPos.z)
        if (horizontalDist <= 1.0) return null

        // Pearl spawns 0.1 below the eye (EntityThrowable / ThrowableProjectile).
        val launch = Vec3(spawnPos.x, spawnPos.y - 0.1, spawnPos.z)
        val (lookDir, timeTicks) = findLookDirAndTime(launch, dest, highArc)

        val aimDist = if (highArc) HIGH_DIST else LOW_DIST
        val aimPoint = eyePos.add(lookDir.scale(aimDist))

        val yawDeg = Math.toDegrees(atan2(-lookDir.x, lookDir.z))
        val pitchDeg = Math.toDegrees(asin(-lookDir.y))

        val flightTimeMs = (timeTicks * MS_PER_TICK).toLong().coerceAtLeast(0L)

        return PearlSolution(
            aimPoint = aimPoint,
            flightTimeMs = flightTimeMs,
            lookYawDeg = yawDeg,
            lookPitchDeg = pitchDeg,
        )
    }
}