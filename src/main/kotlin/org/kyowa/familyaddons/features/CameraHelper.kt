package org.kyowa.familyaddons.features

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.CameraType
import org.kyowa.familyaddons.config.FamilyConfigManager
import org.lwjgl.glfw.GLFW

/**
 * Camera tweaks — clip-through-blocks, custom distance, no-front-camera, and
 * freelook.
 *
 * Clip + Distance are implemented in CameraMixin (reads the config accessors
 * below). No-Front-Camera and the Freelook key polling are tick-driven from
 * the listener registered in register().
 *
 * ─── Freelook ────────────────────────────────────────────────────────────────
 * Hold (or toggle) the freelook key to spin the camera around your character
 * without rotating your character. Camera POSITION still orbits the player
 * based on the player's actual facing (you stay anchored behind your character
 * as you walk), only the camera's VIEW DIRECTION is overridden.
 *
 * Implementation:
 *   1. Key polling here — on press: capture player.yRot + 180 and player.xRot
 *      into freelookYaw/Pitch, force THIRD_PERSON_BACK. On release/toggle-off:
 *      restore previous perspective.
 *   2. MouseHandler delta capture — FreelookEntityMixin intercepts the local player's
 *      Entity.changeLookDirection while freelook is active, routes deltas into
 *      our yaw/pitch, and cancels the original so the player doesn't rotate.
 *   3. Camera rotation override — CameraMixin's TAIL injector on Camera.update
 *      calls setRotation(freelookYaw, freelookPitch) when freelookActive.
 *
 * MouseHandler sensitivity is multiplied by 0.15 to match vanilla's internal cursor-
 * delta-to-degrees conversion.
 *
 * Note on field access from Java mixins:
 *   We use @JvmField on the freelook state so Java code can read CameraHelper.
 *   freelookActive directly (no getter call). With @JvmStatic var + private set
 *   the field is private under the hood and the Java side gets a no-such-symbol
 *   error. @JvmField makes it a real public static field.
 */
object CameraHelper {

    // ── Config accessors (read by CameraMixin) ────────────────────────────

    @JvmStatic
    fun isClipEnabled(): Boolean =
        FamilyConfigManager.config.utilities.cameraClip

    /** Returns the camera distance to use, or null if vanilla 4.0 should be used. */
    @JvmStatic
    fun getCustomDistance(): Float? {
        val cfg = FamilyConfigManager.config.utilities
        if (!cfg.cameraDistEnabled) return null
        return cfg.cameraDist.coerceIn(3f, 12f)
    }

    // ── Freelook state (public via @JvmField for Java mixin access) ───────

    @JvmField @Volatile var freelookActive: Boolean = false
    @JvmField @Volatile var freelookYaw: Float = 0f
    @JvmField @Volatile var freelookPitch: Float = 0f

    private var savedCameraType: CameraType? = null
    private var keyWasDown: Boolean = false

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            tickFreelook(client)
            tickNoFrontCamera(client)
        }
    }

    // ── Freelook tick ─────────────────────────────────────────────────────

    private fun tickFreelook(client: Minecraft) {
        val cfg = FamilyConfigManager.config.utilities

        if (!cfg.freelookEnabled || cfg.freelookKey == GLFW.GLFW_KEY_UNKNOWN) {
            if (freelookActive) disableFreelook(client)
            keyWasDown = false
            return
        }

        // Don't read keybind while a screen is open (typing in chat shouldn't
        // trigger freelook even if the bound key is a letter).
        if (client.gui.screen() != null) {
            if (freelookActive && !cfg.freelookToggleMode) disableFreelook(client)
            keyWasDown = false
            return
        }

        val isDown = GLFW.glfwGetKey(client.window.handle(), cfg.freelookKey) == GLFW.GLFW_PRESS

        if (cfg.freelookToggleMode) {
            // Toggle: each fresh press flips state.
            if (isDown && !keyWasDown) {
                if (freelookActive) disableFreelook(client) else enableFreelook(client)
            }
        } else {
            // Hold: enable on press, disable on release.
            if (isDown && !freelookActive) enableFreelook(client)
            else if (!isDown && freelookActive) disableFreelook(client)
        }

        keyWasDown = isDown
    }

    private fun enableFreelook(client: Minecraft) {
        val player = client.player ?: return
        // +180 so the initial freelook view matches what 3rd-person-back was
        // already showing (camera looks AT the player from behind).
        freelookYaw = player.yRot + 180f
        freelookPitch = player.xRot.coerceIn(-90f, 90f)
        freelookActive = true

        savedCameraType = client.options.cameraType
        if (savedCameraType != CameraType.THIRD_PERSON_BACK) {
            client.options.cameraType = CameraType.THIRD_PERSON_BACK
        }
    }

    private fun disableFreelook(client: Minecraft) {
        freelookActive = false
        savedCameraType?.let { client.options.cameraType = it }
        savedCameraType = null
    }

    /**
     * Called from FreelookEntityMixin when the local player's changeLookDirection
     * fires. We apply the delta to our own yaw/pitch instead of the player.
     * 0.15 matches vanilla's internal cursorDelta -> degrees scale.
     */
    @JvmStatic
    fun applyMouseDelta(cursorDeltaX: Double, cursorDeltaY: Double) {
        if (!freelookActive) return
        val dx = (cursorDeltaX * 0.15).toFloat()
        val dy = (cursorDeltaY * 0.15).toFloat()
        // Wrap yaw to keep it bounded.
        freelookYaw = ((freelookYaw + dx) % 360f + 360f) % 360f
        freelookPitch = (freelookPitch + dy).coerceIn(-90f, 90f)
    }

    // ── No Front Camera ───────────────────────────────────────────────────

    private fun tickNoFrontCamera(client: Minecraft) {
        if (!FamilyConfigManager.config.utilities.noFrontCamera) return
        if (freelookActive) return // freelook forces 3rd-back already
        if (client.options.cameraType == CameraType.THIRD_PERSON_FRONT) {
            client.options.cameraType = CameraType.FIRST_PERSON
        }
    }
}