package org.kyowa.familyaddons.features

import com.google.gson.GsonBuilder
import org.kyowa.familyaddons.util.FaChat
import com.google.gson.reflect.TypeToken
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.world.phys.Vec3
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import org.kyowa.familyaddons.config.FamilyConfigManager
import org.lwjgl.glfw.GLFW
import java.io.File
import org.kyowa.familyaddons.features.FamilyRenderTypes
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.resources.Identifier

object Parkour {

    data class Checkpoint(
        val x: Double, val y: Double, val z: Double,
        val yaw: Float, val pitch: Float = 0f
    )

    data class ParkourRoute(
        val checkpoints: MutableList<Checkpoint> = mutableListOf(),
        var bestTimeMs: Long = Long.MAX_VALUE
    )

    data class SaveData(
        val parkours: MutableMap<String, ParkourRoute> = mutableMapOf(),
        var hudX: Int = 10, var hudY: Int = 60, var hudScale: Float = 1.0f,
        var arrowHudX: Int = 100, var arrowHudY: Int = 100, var arrowHudScale: Float = 1.0f,
        var cpHudX: Int = 200, var cpHudY: Int = 150, var cpHudScale: Float = 1.5f
    )

    var hudX = 10; var hudY = 60; var hudScale = 1.0f
    var arrowHudX = 100; var arrowHudY = 100; var arrowHudScale = 1.0f
    var cpHudX = 200; var cpHudY = 150; var cpHudScale = 1.5f

    private var cpNotifText = ""
    private var cpNotifTimer = 0
    private var startKeyWasDown = false

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val saveFile get() = File(
        Minecraft.getInstance().gameDirectory,
        "config/familyaddons/parkours.json"
    )

    var saveData = SaveData()

    // Active route during editing/running
    private var activeRouteName = "default"
    private val activeRoute get() = saveData.parkours.getOrPut(activeRouteName) { ParkourRoute() }
    private val activeRouteOrNull get() = saveData.parkours[activeRouteName]

    private var isActive = false
    private var nextCheckpointIdx = 0
    private var timerStartMs = 0L
    private var currentTimeMs = 0L
    private var lastPos: Vec3? = null
    private var finishDisplayTimer = 0
    var isEditing = false

    fun load() {
        try {
            if (!saveFile.exists()) {
                val bundled = Parkour::class.java.getResourceAsStream("/default_parkours.json")
                if (bundled != null) {
                    saveFile.parentFile.mkdirs()
                    saveFile.writeText(bundled.bufferedReader().readText())
                }
            }
            if (saveFile.exists()) {
                val type = object : TypeToken<SaveData>() {}.type
                saveData = gson.fromJson(saveFile.readText(), type) ?: SaveData()
            }
        } catch (e: Exception) { e.printStackTrace(); saveData = SaveData() }
        activeRouteName = FamilyConfigManager.config.parkour.activeParkour
        hudX = saveData.hudX; hudY = saveData.hudY; hudScale = saveData.hudScale
        arrowHudX = saveData.arrowHudX; arrowHudY = saveData.arrowHudY; arrowHudScale = saveData.arrowHudScale
        cpHudX = saveData.cpHudX; cpHudY = saveData.cpHudY; cpHudScale = saveData.cpHudScale
    }

    fun save() {
        try {
            saveData.hudX = hudX; saveData.hudY = hudY; saveData.hudScale = hudScale
            saveData.arrowHudX = arrowHudX; saveData.arrowHudY = arrowHudY; saveData.arrowHudScale = arrowHudScale
            saveData.cpHudX = cpHudX; saveData.cpHudY = cpHudY; saveData.cpHudScale = cpHudScale
            saveFile.parentFile.mkdirs()
            saveFile.writeText(gson.toJson(saveData))
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun register() {
        load()

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            val player = client.player ?: return@register
            val world = client.level ?: return@register
            if (finishDisplayTimer > 0) finishDisplayTimer--
            if (cpNotifTimer > 0) cpNotifTimer--

            val config = FamilyConfigManager.config.parkour
            if (!config.enabled) return@register

            // Start keybind — ignore if any screen is open (chat, inventory etc)
            val startKey = config.startKey
            if (startKey != GLFW.GLFW_KEY_UNKNOWN && !isActive && client.gui.screen() == null) {
                val window = client.window.handle()
                val pressed = GLFW.glfwGetKey(window, startKey) == GLFW.GLFW_PRESS
                if (pressed && !startKeyWasDown) start(config.activeParkour)
                startKeyWasDown = pressed
            } else if (startKey == GLFW.GLFW_KEY_UNKNOWN || client.gui.screen() != null) startKeyWasDown = false

            if (!isActive) { lastPos = Vec3(player.x, player.y, player.z); return@register }
            val route = activeRoute
            if (route.checkpoints.isEmpty()) return@register

            val cur = Vec3(player.x, player.y, player.z)
            val prev = lastPos ?: cur
            lastPos = cur

            val cp = route.checkpoints[nextCheckpointIdx]
            if (crossedPlane(prev, cur, cp)) {
                repeat(20) { i ->
                    val angle = i * (Math.PI * 2.0 / 20)
                    val yawRad = Math.toRadians(cp.yaw.toDouble())
                    world.addParticle(ParticleTypes.HAPPY_VILLAGER, true, false,
                        cp.x + Math.cos(angle) * Math.cos(yawRad) * RING_RADIUS,
                        cp.y + Math.sin(angle) * RING_RADIUS,
                        cp.z + Math.cos(angle) * Math.sin(yawRad) * RING_RADIUS,
                        0.0, 0.1, 0.0)
                }
                when {
                    nextCheckpointIdx == 0 -> {
                        timerStartMs = System.currentTimeMillis(); currentTimeMs = 0L; nextCheckpointIdx = 1
                        cpNotifText = "§a§lGO!"; cpNotifTimer = 60; chat("§aGo!")
                    }
                    nextCheckpointIdx == route.checkpoints.size - 1 -> {
                        val elapsed = System.currentTimeMillis() - timerStartMs
                        currentTimeMs = elapsed
                        if (elapsed < route.bestTimeMs) {
                            route.bestTimeMs = elapsed; save()
                            chat("§6§lNew best! §f${formatTime(elapsed)}")
                        } else chat("§eFinished! §f${formatTime(elapsed)} §7| Best: §a${formatTime(route.bestTimeMs)}")
                        cpNotifText = "§6§lFINISH! §f${formatTime(elapsed)}"; cpNotifTimer = 120
                        finishDisplayTimer = 100; isActive = false; nextCheckpointIdx = 0
                    }
                    else -> {
                        nextCheckpointIdx++
                        cpNotifText = "§e§lCheckpoint $nextCheckpointIdx/${route.checkpoints.size - 1}"; cpNotifTimer = 60
                    }
                }
            }
            if (isActive && timerStartMs > 0) currentTimeMs = System.currentTimeMillis() - timerStartMs
        }

        // Timer HUD
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("familyaddons", "hud_parkour_1"), HudElement { ctx, _ ->
            if (!isActive && finishDisplayTimer <= 0) return@HudElement
            val tr = Minecraft.getInstance().font
            val m = ctx.pose()
            m.pushMatrix(); m.translate(hudX.toFloat(), hudY.toFloat()); m.scale(hudScale, hudScale)
            val timeStr = formatTime(currentTimeMs)
            val best = activeRoute.bestTimeMs
            val bestStr = if (best == Long.MAX_VALUE) "§7--:--.---" else "§a${formatTime(best)}"
            ctx.text(tr, if (isActive) "§f$timeStr" else "§f$timeStr  §7Best: $bestStr", 0, 0, -1, true)
            m.popMatrix()
        })

        // Checkpoint notification HUD
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("familyaddons", "hud_parkour_2"), HudElement { ctx, _ ->
            if (cpNotifTimer <= 0) return@HudElement
            val alpha = (cpNotifTimer.toFloat() / 40f).coerceIn(0f, 1f)
            val tr = Minecraft.getInstance().font
            val m = ctx.pose()
            m.pushMatrix(); m.translate(cpHudX.toFloat(), cpHudY.toFloat()); m.scale(cpHudScale, cpHudScale)
            ctx.text(tr, cpNotifText, 0, 0, ((alpha * 255).toInt() shl 24) or 0xFFFFFF, true)
            m.popMatrix()
        })

        // Arrow HUD
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("familyaddons", "hud_parkour_3"), HudElement { ctx, _ ->
            if (!isActive || nextCheckpointIdx >= activeRoute.checkpoints.size) return@HudElement
            val player = Minecraft.getInstance().player ?: return@HudElement
            val tr = Minecraft.getInstance().font
            val cp = activeRoute.checkpoints[nextCheckpointIdx]
            val dx = cp.x - player.x; val dz = cp.z - player.z
            val dist = Math.sqrt(dx * dx + dz * dz)
            val bearing = Math.toDegrees(Math.atan2(dx, -dz))
            var rel = bearing - (player.yRot + 180.0)
            while (rel > 180) rel -= 360; while (rel < -180) rel += 360
            val arrow = when {
                rel < -157.5 || rel > 157.5 -> "↓"
                rel < -112.5 -> "↙"; rel < -67.5 -> "←"; rel < -22.5 -> "↖"
                rel < 22.5 -> "↑"; rel < 67.5 -> "↗"; rel < 112.5 -> "→"; else -> "↘"
            }
            val distStr = if (dist > 1000) "${"%.1f".format(dist/1000)}km" else "${"%.0f".format(dist)}m"
            val m = ctx.pose()
            m.pushMatrix(); m.translate(arrowHudX.toFloat(), arrowHudY.toFloat()); m.scale(arrowHudScale, arrowHudScale)
            ctx.text(tr, "§e$arrow", 0, 0, -1, true)
            ctx.text(tr, "§f$distStr", 0, 12, -1, true)
            m.popMatrix()
        })
    }

    // --- Geometry ---
    private const val RING_RADIUS = 1.5f
    private const val RING_SEGMENTS = 32
    private const val TUBE_RADIUS = 0.18f
    private const val TUBE_SEGMENTS = 12

    private fun ringNormal(yaw: Float, pitch: Float): Vec3 {
        val y = Math.toRadians(yaw.toDouble()); val p = Math.toRadians(pitch.toDouble())
        return Vec3(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p)).normalize()
    }
    private fun ringRight(yaw: Float): Vec3 {
        val y = Math.toRadians(yaw.toDouble())
        return Vec3(Math.cos(y), 0.0, Math.sin(y)).normalize()
    }
    private fun ringUp(yaw: Float, pitch: Float): Vec3 {
        val n = ringNormal(yaw, pitch); val r = ringRight(yaw)
        return Vec3(r.y*n.z - r.z*n.y, r.z*n.x - r.x*n.z, r.x*n.y - r.y*n.x).normalize()
    }

    private fun crossedPlane(prev: Vec3, cur: Vec3, cp: Checkpoint): Boolean {
        val center = Vec3(cp.x, cp.y, cp.z)
        val normal = ringNormal(cp.yaw, cp.pitch)
        val dPrev = prev.subtract(center).dot(normal)
        val dCur = cur.subtract(center).dot(normal)
        if (dPrev * dCur > 0) return false
        val mid = prev.add(cur).scale(0.5)
        val diff = mid.subtract(center)
        val lateral = diff.subtract(normal.scale(diff.dot(normal)))
        return lateral.length() < RING_RADIUS + 1.0
    }

    fun onWorldRender(matrices: PoseStack, collector: SubmitNodeCollector, cam: Vec3) {
        val route = activeRoute
        if (route.checkpoints.isEmpty()) return
        if (!isActive && !isEditing) return

        val camX = cam.x; val camY = cam.y; val camZ = cam.z

        fun drawAll(alpha: Float, renderType: net.minecraft.client.renderer.rendertype.RenderType) {
            collector.submitCustomGeometry(matrices, renderType) { entry, buf ->
                route.checkpoints.forEachIndexed { i, cp ->
                    if (!isEditing && i < nextCheckpointIdx) return@forEachIndexed
                    val isNext = i == nextCheckpointIdx
                    val r = if (isNext) 0.2f else 0.8f
                    val g = if (isNext) 1.0f else 0.8f
                    val b = if (isNext) 0.2f else 0.8f
                    drawTorus(buf, entry, cp, camX, camY, camZ, r, g, b, alpha)
                }
            }
        }

        drawAll(0.9f, FamilyRenderTypes.LINES)
        drawAll(0.15f, FamilyRenderTypes.LINES_NO_DEPTH)
    }

    private fun drawTorus(
        buf: com.mojang.blaze3d.vertex.VertexConsumer, mat: PoseStack.Pose,
        cp: Checkpoint, camX: Double, camY: Double, camZ: Double,
        r: Float, g: Float, b: Float, alpha: Float
    ) {
        val cx = (cp.x - camX).toFloat(); val cy = (cp.y - camY).toFloat(); val cz = (cp.z - camZ).toFloat()
        val right = ringRight(cp.yaw); val up = ringUp(cp.yaw, cp.pitch); val normal = ringNormal(cp.yaw, cp.pitch)
        val nx = normal.x.toFloat(); val ny = normal.y.toFloat(); val nz = normal.z.toFloat()

        for (i in 0 until RING_SEGMENTS) {
            val a0 = (i.toDouble() / RING_SEGMENTS) * Math.PI * 2
            val a1 = ((i + 1).toDouble() / RING_SEGMENTS) * Math.PI * 2
            for (j in 0 until TUBE_SEGMENTS) {
                val p0 = (j.toDouble() / TUBE_SEGMENTS) * Math.PI * 2
                val p1 = ((j + 1).toDouble() / TUBE_SEGMENTS) * Math.PI * 2
                val v00 = tpt(cx, cy, cz, right, up, normal, a0, p0)
                val v10 = tpt(cx, cy, cz, right, up, normal, a1, p0)
                val v11 = tpt(cx, cy, cz, right, up, normal, a1, p1)
                val v01 = tpt(cx, cy, cz, right, up, normal, a0, p1)
                buf.addVertex(mat.pose(), v00[0], v00[1], v00[2]).setColor(r, g, b, alpha).setNormal(mat, nx, ny, nz).setLineWidth(2.0f)
                buf.addVertex(mat.pose(), v10[0], v10[1], v10[2]).setColor(r, g, b, alpha).setNormal(mat, nx, ny, nz).setLineWidth(2.0f)
                buf.addVertex(mat.pose(), v00[0], v00[1], v00[2]).setColor(r, g, b, alpha).setNormal(mat, nx, ny, nz).setLineWidth(2.0f)
                buf.addVertex(mat.pose(), v01[0], v01[1], v01[2]).setColor(r, g, b, alpha).setNormal(mat, nx, ny, nz).setLineWidth(2.0f)
            }
        }
    }

    private fun tpt(cx: Float, cy: Float, cz: Float, right: Vec3, up: Vec3, normal: Vec3, a: Double, p: Double): FloatArray {
        val ox = (right.x * Math.cos(a) + up.x * Math.sin(a)).toFloat()
        val oy = (right.y * Math.cos(a) + up.y * Math.sin(a)).toFloat()
        val oz = (right.z * Math.cos(a) + up.z * Math.sin(a)).toFloat()
        val tcx = cx + ox * RING_RADIUS; val tcy = cy + oy * RING_RADIUS; val tcz = cz + oz * RING_RADIUS
        return floatArrayOf(
            tcx + (ox * Math.cos(p) + normal.x.toFloat() * Math.sin(p)).toFloat() * TUBE_RADIUS,
            tcy + (oy * Math.cos(p) + normal.y.toFloat() * Math.sin(p)).toFloat() * TUBE_RADIUS,
            tcz + (oz * Math.cos(p) + normal.z.toFloat() * Math.sin(p)).toFloat() * TUBE_RADIUS
        )
    }

    fun hasRings() = activeRoute.checkpoints.isNotEmpty() && (isActive || isEditing)

    // --- Commands ---
    fun addCheckpoint(x: Double, y: Double, z: Double, yaw: Float, pitch: Float) {
        activeRoute.checkpoints.add(Checkpoint(x, y, z, yaw, pitch)); save()
        chat("§aCheckpoint ${activeRoute.checkpoints.size} added to §e$activeRouteName")
    }
    fun removeLast() {
        if (activeRoute.checkpoints.isEmpty()) { chat("§cNo checkpoints."); return }
        activeRoute.checkpoints.removeLast(); save()
        chat("§eRemoved last. §7${activeRoute.checkpoints.size} remaining.")
    }
    fun clearAll() {
        activeRoute.checkpoints.clear(); activeRoute.bestTimeMs = Long.MAX_VALUE; save()
        chat("§cCleared §e$activeRouteName")
    }

    fun start(name: String = activeRouteName) {
        val route = saveData.parkours[name]
        if (route == null || route.checkpoints.size < 2) {
            chat("§cParkour §e$name §chas less than 2 checkpoints or doesn't exist."); return
        }
        activeRouteName = name
        isActive = true; isEditing = false; nextCheckpointIdx = 0
        currentTimeMs = 0L; timerStartMs = 0L
        chat("§aStarted §e$name§a! Run to the §egreen ring§a!")
    }
    fun stop() { isActive = false; nextCheckpointIdx = 0; currentTimeMs = 0L; chat("§cRun stopped.") }
    fun edit(name: String = FamilyConfigManager.config.parkour.activeParkour) {
        activeRouteName = name
        isEditing = !isEditing
        chat(if (isEditing) "§aEdit mode ON §7— editing §e$activeRouteName" else "§7Edit mode OFF")
    }
    fun listCheckpoints() {
        if (activeRoute.checkpoints.isEmpty()) { chat("§cNo checkpoints in §e$activeRouteName§c."); return }
        chat("§6$activeRouteName §7(${activeRoute.checkpoints.size} checkpoints):")
        activeRoute.checkpoints.forEachIndexed { i, cp ->
            val label = when(i) { 0 -> "§aSTART"; activeRoute.checkpoints.size-1 -> "§cEND"; else -> "§7CP $i" }
            chat("  $label §f${cp.x.toInt()}, ${cp.y.toInt()}, ${cp.z.toInt()}")
        }
        if (activeRoute.bestTimeMs != Long.MAX_VALUE) chat("§6Best: §f${formatTime(activeRoute.bestTimeMs)}")
    }
    fun listParkours() {
        val parkours = saveData.parkours.filter { it.value.checkpoints.isNotEmpty() || it.key == activeRouteName }
        if (parkours.isEmpty()) { chat("§cNo parkours saved."); return }
        chat("§6Parkours (active: §e$activeRouteName§6):")
        parkours.forEach { (name, route) ->
            val best = if (route.bestTimeMs == Long.MAX_VALUE) "§7no best" else "§a${formatTime(route.bestTimeMs)}"
            val active = if (name == activeRouteName) "§a✔ " else "  "
            chat("$active§e$name §7— ${route.checkpoints.size} checkpoints — $best")
        }
    }
    fun resetBest() { activeRoute.bestTimeMs = Long.MAX_VALUE; save(); chat("§cBest reset for §e$activeRouteName") }
    fun deleteParkour(name: String) {
        saveData.parkours.remove(name); save(); chat("§cDeleted parkour §e$name")
    }
    fun selectParkour(name: String) {
        activeRouteName = name
        FamilyConfigManager.config.parkour.activeParkour = name
        FamilyConfigManager.save()
        val exists = saveData.parkours.containsKey(name)
        chat(if (exists) "§aSelected parkour: §e$name" else "§aCreated new parkour: §e$name §7(use /faparkour add to add checkpoints)")
    }

    private fun formatTime(ms: Long): String {
        if (ms == Long.MAX_VALUE) return "--:--.---"
        return "%d:%02d.%03d".format(ms / 60000, (ms % 60000) / 1000, ms % 1000)
    }
    private fun chat(msg: String) {
        Minecraft.getInstance().execute {
            Minecraft.getInstance().player?.sendSystemMessage(FaChat.prefixed("$msg"))
        }
    }
    internal fun drawBoxEdges(
        buf: com.mojang.blaze3d.vertex.VertexConsumer,
        entry: com.mojang.blaze3d.vertex.PoseStack.Pose,
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        r: Float, g: Float, b: Float, a: Float
    ) {
        val edges = arrayOf(
            floatArrayOf(x1,y1,z1,x2,y1,z1), floatArrayOf(x2,y1,z1,x2,y1,z2),
            floatArrayOf(x2,y1,z2,x1,y1,z2), floatArrayOf(x1,y1,z2,x1,y1,z1),
            floatArrayOf(x1,y2,z1,x2,y2,z1), floatArrayOf(x2,y2,z1,x2,y2,z2),
            floatArrayOf(x2,y2,z2,x1,y2,z2), floatArrayOf(x1,y2,z2,x1,y2,z1),
            floatArrayOf(x1,y1,z1,x1,y2,z1), floatArrayOf(x2,y1,z1,x2,y2,z1),
            floatArrayOf(x2,y1,z2,x2,y2,z2), floatArrayOf(x1,y1,z2,x1,y2,z2)
        )
        for (e in edges) {
            val dx = e[3]-e[0]; val dy = e[4]-e[1]; val dz = e[5]-e[2]
            buf.addVertex(entry, e[0], e[1], e[2]).setColor(r, g, b, a).setNormal(entry, dx, dy, dz).setLineWidth(2.0f)
            buf.addVertex(entry, e[3], e[4], e[5]).setColor(r, g, b, a).setNormal(entry, dx, dy, dz).setLineWidth(2.0f)
        }
    }
}
