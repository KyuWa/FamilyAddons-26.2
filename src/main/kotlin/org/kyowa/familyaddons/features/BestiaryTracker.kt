package org.kyowa.familyaddons.features

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.resources.Identifier
import org.kyowa.familyaddons.COLOR_CODE_REGEX
import org.kyowa.familyaddons.FamilyAddons
import org.kyowa.familyaddons.config.FamilyConfigManager

object BestiaryTracker {

    // ── Displayed values ──────────────────────────────────────────────
    var kills: Int = 0                  // total delta kills for current mob (persisted)
    var bestiaryProgress: String = "?"  // e.g. "13,000/20,000" or "MAX"

    // ── Internal tracking ─────────────────────────────────────────────
    private var lastRawProgress: Int = -1
    private var sessionKillDelta: Int = 0
    private var lastKnownMobName: String = ""
    private var autoMobName: String = ""       // mob name grabbed from tablist when auto mode on
    private var autoTickCounter = 0            // separate counter for 10s auto-grab poll

    // ── Session ───────────────────────────────────────────────────────
    // Timer starts on first kill. Pauses when tablist bestiary number hasn't
    // changed for 20 seconds. Resumes the moment a new kill is detected.
    //
    // How pause/resume works without drift:
    //   - accumulatedMs  = total active time banked before the current active stretch
    //   - activeStartMs  = when the current active stretch began (0 = paused)
    //   - elapsed        = accumulatedMs + (now - activeStartMs)  [when running]
    //                    = accumulatedMs                           [when paused]
    private var sessionActive: Boolean = false
    private var accumulatedMs: Long = 0L   // banked uptime before current stretch
    private var activeStartMs: Long = 0L   // when current active stretch started (0 = paused)
    private var lastProgressChangeMs: Long = 0L  // last time the tablist number changed

    // ── Tick / mouse state ────────────────────────────────────────────
    private var tickCounter = 0
    private var mouseWasDown = false
    private var resetMouseWasDown = false

    // ── HUD proxy to config ───────────────────────────────────────────
    var hudX: Int
        get() = FamilyConfigManager.config.highlight.bestiaryHudX
        set(v) { FamilyConfigManager.config.highlight.bestiaryHudX = v }
    var hudY: Int
        get() = FamilyConfigManager.config.highlight.bestiaryHudY
        set(v) { FamilyConfigManager.config.highlight.bestiaryHudY = v }
    var hudScale: Float
        get() = FamilyConfigManager.config.highlight.bestiaryHudScale
        set(v) { FamilyConfigManager.config.highlight.bestiaryHudScale = v }

    fun save() = FamilyConfigManager.save()

    // ── HUD dimensions ────────────────────────────────────────────────
    const val HUD_W = 180

    fun hudH(): Int {
        var h = 38 // title(12) + kills(10) + bestiaryKills(10) + padding(6)
        if (FamilyConfigManager.config.highlight.displayMode == 1) h += 10 // uptime line
        return h
    }

    // ── Register ──────────────────────────────────────────────────────
    fun register() {

        // Poll tablist every 60 ticks (~3s)
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (!FamilyConfigManager.config.highlight.bestiaryHudEnabled) return@register
            if (tickCounter++ % 60 != 0) return@register
            parseTablist(client)
            // Also update zone highlight MAX check using fresh tablist data
            BestiaryZoneHighlight.checkMaxFromTablist()
        }

        // Auto-grab mob name from tablist every 10s when enabled and text box is empty
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (!FamilyConfigManager.config.highlight.bestiaryHudEnabled) return@register
            if (!FamilyConfigManager.config.highlight.autoMobName) return@register
            if (FamilyConfigManager.config.highlight.mobName.isNotBlank()) return@register
            if (autoTickCounter++ % 200 != 0) return@register
            grabMobNameFromTablist(client)
        }

        // Mode switcher click (inventory only)
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (!FamilyConfigManager.config.highlight.bestiaryHudEnabled) return@register
            if (client.gui.screen() !is InventoryScreen) { mouseWasDown = false; return@register }

            val mouseDown = org.lwjgl.glfw.GLFW.glfwGetMouseButton(
                client.window.handle(), org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT
            ) == org.lwjgl.glfw.GLFW.GLFW_PRESS

            if (mouseDown && !mouseWasDown) {
                val mx = client.mouseHandler.getScaledXPos(client.window)
                val my = client.mouseHandler.getScaledYPos(client.window)
                if (isHoveringModeLabel(mx, my)) {
                    val cfg = FamilyConfigManager.config.highlight
                    cfg.displayMode = if (cfg.displayMode == 0) 1 else 0
                    if (cfg.displayMode == 1 && !sessionActive) startSession()
                    FamilyConfigManager.save()
                }
            }
            mouseWasDown = mouseDown
        }

        // Reset session click (inventory only, session mode only)
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (!FamilyConfigManager.config.highlight.bestiaryHudEnabled) return@register
            if (client.gui.screen() !is InventoryScreen) { resetMouseWasDown = false; return@register }

            val mouseDown = org.lwjgl.glfw.GLFW.glfwGetMouseButton(
                client.window.handle(), org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT
            ) == org.lwjgl.glfw.GLFW.GLFW_PRESS

            if (mouseDown && !resetMouseWasDown) {
                val mx = client.mouseHandler.getScaledXPos(client.window)
                val my = client.mouseHandler.getScaledYPos(client.window)
                if (isHoveringResetLabel(mx, my)) resetSession()
            }
            resetMouseWasDown = mouseDown
        }

        // HUD render — 26.1 extract-pipeline HUD element (replaces HudRenderCallback)
        HudElementRegistry.addLast(
            Identifier.fromNamespaceAndPath("familyaddons", "bestiary_hud"),
            HudElement { ctx, _ -> renderBestiaryHud(ctx) }
        )
    }

    private fun renderBestiaryHud(ctx: GuiGraphicsExtractor) {
        val cfg = FamilyConfigManager.config.highlight
        if (!cfg.bestiaryHudEnabled) return
        // Show HUD if manual name set OR auto-detect is on (may not have grabbed yet)
        val hasTarget = cfg.mobName.isNotBlank() || (cfg.autoMobName && autoMobName.isNotBlank())
        if (!hasTarget) return
        val client = Minecraft.getInstance()
        val tr = client.font
        val isSession = cfg.displayMode == 1
        // Use manual text box if filled, else auto-grabbed name, else "?"
        val mobName = when {
            cfg.mobName.isNotBlank() -> cfg.mobName
            cfg.autoMobName && autoMobName.isNotBlank() -> autoMobName
            else -> "?"
        }
        val isInventoryOpen = client.gui.screen() is InventoryScreen

        val m = ctx.pose()
        m.pushMatrix()
        m.translate(cfg.bestiaryHudX.toFloat(), cfg.bestiaryHudY.toFloat())
        m.scale(cfg.bestiaryHudScale, cfg.bestiaryHudScale)

        var y = 3

        // Title
        ctx.text(tr, "§6§l$mobName Bestiary", 4, y, -1, true)
        y += 12

        // Kills
        val killsDisplay = if (isSession) sessionKillDelta else kills
        ctx.text(tr, "§eKills: §f${"%,d".format(killsDisplay)}", 4, y, -1, true)
        y += 10

        // Bestiary Kills + mode tag when inventory open
        if (isInventoryOpen) {
            val modeStr = if (isSession) "§a[Session]" else "§a[Total]"
            ctx.text(tr, "§eBestiary Kills: §f$bestiaryProgress  $modeStr", 4, y, -1, true)
        } else {
            ctx.text(tr, "§eBestiary Kills: §f$bestiaryProgress", 4, y, -1, true)
        }
        y += 10

        // Uptime — session mode only, starts ticking from first kill
        if (isSession) {
            val elapsed = getUptime()
            ctx.text(tr, "§eUptime: §f${formatTime(elapsed)}", 4, y, -1, true)
            y += 10

            // Reset button — only visible when inventory open
            if (isInventoryOpen) {
                ctx.text(tr, "§c[Reset Session]", 4, y, -1, true)
            }
        }

        m.popMatrix()

        // Tooltips when inventory open
        if (isInventoryOpen) {
            val mx = client.mouseHandler.getScaledXPos(client.window)
            val my = client.mouseHandler.getScaledYPos(client.window)
            if (isHoveringModeLabel(mx, my)) {
                renderModeTooltip(ctx, mx.toInt(), my.toInt(), isSession)
            } else if (isSession && isHoveringResetLabel(mx, my)) {
                renderResetTooltip(ctx, mx.toInt(), my.toInt())
            }
        }
    }

    // ── Hover regions ─────────────────────────────────────────────────
    // Bestiary Kills line: y offset = 3 + 12 + 10 = 25
    private fun isHoveringModeLabel(mx: Double, my: Double): Boolean {
        val cfg = FamilyConfigManager.config.highlight
        val sc = cfg.bestiaryHudScale.toDouble()
        val sx = cfg.bestiaryHudX.toDouble(); val sy = cfg.bestiaryHudY.toDouble()
        return mx >= sx && mx <= sx + HUD_W * sc &&
                my >= sy + 25 * sc && my <= sy + 35 * sc
    }

    // Reset label: after uptime line = 3+12+10+10+10 = 45
    private fun isHoveringResetLabel(mx: Double, my: Double): Boolean {
        val cfg = FamilyConfigManager.config.highlight
        val sc = cfg.bestiaryHudScale.toDouble()
        val sx = cfg.bestiaryHudX.toDouble(); val sy = cfg.bestiaryHudY.toDouble()
        return mx >= sx && mx <= sx + HUD_W * sc &&
                my >= sy + 45 * sc && my <= sy + 55 * sc
    }

    // ── Tooltips ──────────────────────────────────────────────────────
    private fun renderModeTooltip(ctx: net.minecraft.client.gui.GuiGraphicsExtractor, mx: Int, my: Int, isSession: Boolean) {
        renderTooltip(ctx, listOf(
            "§eDisplay Mode", "",
            if (!isSession) "§a▶ Total" else "§7  Total",
            if (isSession)  "§a▶ Session" else "§7  Session",
            "", "§bClick to switch!"
        ), mx, my)
    }

    private fun renderResetTooltip(ctx: net.minecraft.client.gui.GuiGraphicsExtractor, mx: Int, my: Int) {
        renderTooltip(ctx, listOf(
            "§cReset Session", "",
            "§7Resets session kills and uptime.",
            "§7Total kills are kept.",
            "", "§bClick to reset!"
        ), mx, my)
    }

    private fun renderTooltip(ctx: net.minecraft.client.gui.GuiGraphicsExtractor, lines: List<String>, mx: Int, my: Int) {
        val tr = Minecraft.getInstance().font
        val maxW = lines.maxOf { tr.width(it.replace(COLOR_CODE_REGEX, "")) }
        val ttW = maxW + 12; val ttH = lines.size * 10 + 6
        var tx = mx + 10; var ty = my - ttH - 4
        if (tx + ttW > ctx.guiWidth()) tx = mx - ttW - 4
        if (ty < 0) ty = my + 14
        ctx.fill(tx - 1, ty - 1, tx + ttW + 1, ty + ttH + 1, 0xFF1E0030.toInt())
        ctx.fill(tx, ty, tx + ttW, ty + ttH, 0xF0100010.toInt())
        var lineY = ty + 3
        for (line in lines) {
            if (line.isNotEmpty()) ctx.text(tr, line, tx + 6, lineY, -1, true)
            lineY += 10
        }
    }

    // ── Session management ────────────────────────────────────────────
    private fun startSession() {
        accumulatedMs       = 0L
        activeStartMs       = System.currentTimeMillis()
        lastProgressChangeMs = System.currentTimeMillis()
        sessionKillDelta    = 0
        sessionActive       = true
    }

    private fun pauseTimer() {
        if (activeStartMs == 0L) return  // already paused
        accumulatedMs += System.currentTimeMillis() - activeStartMs
        activeStartMs = 0L
    }

    private fun resumeTimer() {
        if (activeStartMs != 0L) return  // already running
        activeStartMs = System.currentTimeMillis()
    }

    private fun getUptime(): Long {
        if (!sessionActive) return 0L
        return if (activeStartMs != 0L)
            accumulatedMs + (System.currentTimeMillis() - activeStartMs)
        else
            accumulatedMs
    }

    private fun resetSession() {
        sessionKillDelta    = 0
        accumulatedMs       = 0L
        activeStartMs       = 0L
        lastProgressChangeMs = 0L
        sessionActive       = false  // timer won't start again until next kill
    }

    // ── Tablist parser ────────────────────────────────────────────────
    private fun parseTablist(client: Minecraft) {
        val tabList = client.connection?.onlinePlayers ?: return
        val cfg = FamilyConfigManager.config.highlight
        val target = cfg.mobName.trim().lowercase()

        // Resolve effective mob name: manual text box takes priority over auto-grabbed
        val effectiveTarget = if (cfg.mobName.isNotBlank()) target
        else if (cfg.autoMobName && autoMobName.isNotBlank()) autoMobName.lowercase()
        else return  // nothing to track

        // Fix 3: detect mob name change → reset counts, load saved kills for new mob
        if (effectiveTarget != lastKnownMobName) {
            if (lastKnownMobName.isNotEmpty()) {
                cfg.savedKills[lastKnownMobName] = kills
                FamilyConfigManager.save()
            }
            kills            = cfg.savedKills[effectiveTarget] ?: 0
            lastKnownMobName = effectiveTarget
            lastRawProgress  = -1
            bestiaryProgress = "?"
            sessionKillDelta = 0
            sessionActive    = false
        }

        val sorted = tabList
            .filter { it.tabListDisplayName != null }
            .sortedBy { it.profile.name ?: "" }

        var inBestiary = false

        for (entry in sorted) {
            val raw      = entry.tabListDisplayName!!.string
            val stripped = raw.replace(COLOR_CODE_REGEX, "")
            val clean    = stripped.trim()
            val isIndented = stripped.startsWith(" ")

            if (clean == "Bestiary:") { inBestiary = true; continue }

            if (clean.isNotEmpty() && !isIndented && clean.endsWith(":")) {
                if (inBestiary) inBestiary = false
                continue
            }

            if (!inBestiary || !isIndented) continue

            val trimmed = stripped.trimStart()
            if (!trimmed.lowercase().startsWith(effectiveTarget)) continue

            val colonIdx = trimmed.lastIndexOf(':')
            if (colonIdx < 0) continue
            val value = trimmed.substring(colonIdx + 1).trim()
            if (value.isEmpty()) continue

            bestiaryProgress = value

            when {
                value.equals("MAX", ignoreCase = true) -> { /* no update */ }
                value.contains("/") -> {
                    val num = value.substringBefore("/").replace(",", "").trim().toIntOrNull()
                    if (num != null) {
                        when {
                            lastRawProgress < 0 -> {
                                lastRawProgress = num  // first read — baseline only
                            }
                            num > lastRawProgress -> {
                                val delta = num - lastRawProgress
                                kills += delta

                                if (!sessionActive && cfg.displayMode == 1) {
                                    // First kill — start session and timer
                                    startSession()
                                    sessionKillDelta = delta
                                } else if (sessionActive) {
                                    sessionKillDelta += delta
                                    // Kill detected — record time and resume timer if paused
                                    lastProgressChangeMs = System.currentTimeMillis()
                                    resumeTimer()
                                }

                                cfg.savedKills[effectiveTarget] = kills
                                FamilyConfigManager.save()
                                lastRawProgress = num
                            }
                            num < lastRawProgress -> {
                                lastRawProgress = num
                            }
                            num == lastRawProgress -> {
                                // No change this poll — check if 20s idle, pause if so
                                if (sessionActive && activeStartMs != 0L &&
                                    lastProgressChangeMs > 0L &&
                                    System.currentTimeMillis() - lastProgressChangeMs > 20_000L) {
                                    pauseTimer()
                                }
                            }
                        }
                    }
                }
            }
            break
        }
    }

    // ── Auto-grab mob name from first bestiary entry in tablist ─────────
    private fun grabMobNameFromTablist(client: Minecraft) {
        val tabList = client.connection?.onlinePlayers ?: return
        val sorted = tabList
            .filter { it.tabListDisplayName != null }
            .sortedBy { it.profile.name ?: "" }

        var inBestiary = false
        for (entry in sorted) {
            val raw      = entry.tabListDisplayName!!.string
            val stripped = raw.replace(COLOR_CODE_REGEX, "")
            val clean    = stripped.trim()
            val isIndented = stripped.startsWith(" ")

            if (clean == "Bestiary:") { inBestiary = true; continue }
            if (clean.isNotEmpty() && !isIndented && clean.endsWith(":")) {
                if (inBestiary) inBestiary = false
                continue
            }
            if (!inBestiary || !isIndented) continue

            // Entry: " Lapis Zombie 5: 243/400"
            // Strip the tier number and value, keep just the mob name
            val trimmed = stripped.trimStart()
            // Remove trailing " N: xxx/yyy" or " N: MAX" — everything from last digit-colon onwards
            val mobName = trimmed.replace(Regex("\\s+\\d+:\\s+.*$"), "").trim()
            if (mobName.isNotBlank() && mobName != autoMobName) {
                autoMobName = mobName
                FamilyAddons.LOGGER.info("BestiaryTracker: auto-grabbed mob name '$autoMobName' from tablist")
            }
            return  // only care about the first entry
        }
    }

    // ── Time formatter ────────────────────────────────────────────────
    private fun formatTime(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "${h}h ${m}m ${sec}s" else "${m}m ${sec}s"
    }
}