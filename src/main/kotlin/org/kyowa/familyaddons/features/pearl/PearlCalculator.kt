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

    /**
     * Returns Pair(lookDirectionUnitVector, flightTimeTicks).
     *
     * Primary path: 50-iteration pitch bisection.
     *   - Pitch range: highArc ? [45°, 89°] : [-89°, 45°]
     *   - For each midpoint pitch, compute predicted Y at horizontalDist:
     *       t = horizontalDist / vx_horizontal
     *       predY = (1 - exp(-DRAG*t)) * vy / DRAG  +  acceleration.y * t / DRAG
     *   - Compare predY to actual displacement.y:
     *       predY >= dy → throw goes too high → cap upper bound at midpoint
     *       predY <  dy → throw goes too low  → raise lower bound at midpoint
     *   - The convergenceFlag is set whenever predY >= dy is hit, and the
     *     last such pitch+time become the solution.
     *
     * Fallback path: golden-section minimization on
     *     f(t) = | |velocityGivenTime(t, displacement)|² − squaredSpeed |
     * over t ∈ highArc ? [35, 120] : [1e-6, 60]. Solution velocity gives look dir;
     * solution time is t.
     */
    private fun findLookDirAndTime(
        pos: Vec3,
        target: Vec3,
        highArc: Boolean,
    ): Pair<Vec3, Double> {
        val displacement = target.subtract(pos)
        val dy = displacement.y
        val horizontalDist = sqrt(
            displacement.x * displacement.x + displacement.z * displacement.z
        )

        var lo = if (highArc) 45.0 else -89.0
        var hi = if (highArc) 89.0 else 45.0
        var lastPitchDeg = 0.0
        var lastTimeTicks = 0.0
        var converged = false

        repeat(50) {
            val pitchMidDeg = (lo + hi) / 2.0
            val pitchRad = Math.toRadians(pitchMidDeg)
            val vxHoriz = THROW_SPEED * cos(pitchRad)
            val vy = THROW_SPEED * sin(pitchRad)

            // Time to reach target horizontally — naive (no horizontal drag correction).
            val t = if (vxHoriz > 1e-12) horizontalDist / vxHoriz else 1e9

            val dragFactor = 1.0 - exp(-DRAG * t)
            val riseFromVy = (dragFactor * vy) / DRAG
            val gravityDrop = (acceleration.y * t) / DRAG
            val predY = riseFromVy + gravityDrop

            if (predY >= dy) {
                hi = pitchMidDeg
                lastPitchDeg = pitchMidDeg
                lastTimeTicks = t
                converged = true
            } else {
                lo = pitchMidDeg
            }
        }

        // Compute yaw from displacement (always, regardless of convergence).
        val yawRad = atan2(displacement.x, displacement.z)

        if (converged) {
            val pitchRad = Math.toRadians(lastPitchDeg)
            val lookDir = Vec3(
                -sin(yawRad) * cos(pitchRad),
                -sin(pitchRad),
                cos(yawRad) * cos(pitchRad),
            ).normalize()
            return Pair(lookDir, lastTimeTicks)
        }

        // Fallback: golden-section minimize on velocity magnitude.
        val (tLo, tHi) = if (highArc) Pair(35.0, 120.0) else Pair(1e-6, 60.0)
        val f: (Double) -> Double = { t ->
            val v = velocityGivenTime(t, displacement)
            abs(v.lengthSqr() - squaredSpeed)
        }
        val tMin = minimizeScalarBounded(f, tLo, tHi)
        val velocity = velocityGivenTime(tMin, displacement)
        return Pair(velocity.normalize(), tMin)
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

        val (lookDir, timeTicks) = findLookDirAndTime(spawnPos, dest, highArc)

        val aimDist = if (highArc) HIGH_DIST else LOW_DIST
        val aimPoint = spawnPos.add(lookDir.scale(aimDist))

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