package org.kyowa.familyaddons.features

import com.google.gson.JsonParser
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.gui.components.Button
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.Component
import org.kyowa.familyaddons.FamilyAddons
import org.kyowa.familyaddons.config.FamilyConfigManager
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture

object AutoUpdater {

    private const val GITHUB_REPO = "KyuWa/FamilyAddons-26.2"

    private val http = HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.ALWAYS).build()

    @Volatile var latestVersion: String? = null
        private set
    @Volatile var downloadUrl: String? = null
        private set
    @Volatile var updateAvailable: Boolean = false
        private set
    @Volatile var releaseNotes: List<String> = emptyList()
        private set

    private var checked = false
    var downloading = false
        private set
    var downloaded = false
        private set
    var skipped = false
        private set

    // Tracks whether the last network event was a DISCONNECT (or initial state).
    // Hypixel fires JOIN on every world transition; we only want to notify when
    // the player has actually (re)connected from the server list — i.e. when a
    // JOIN follows a DISCONNECT, not a world hop. Starts true so the first JOIN
    // after launch counts as a real connect.
    @Volatile private var freshConnect: Boolean = true

    // ── Background re-check while playing ─────────────────────────────
    // The launch-time check alone meant a release published mid-session was
    // only discovered on the next launch, which then needed one more restart
    // to actually load it. Re-poll GitHub every 10 minutes (well under the
    // 60 req/h anonymous API limit) and, if enabled, download right away so
    // the next restart already has the new jar.
    private const val RECHECK_TICKS = 20 * 60 * 10
    private var recheckTicker = 0
    @Volatile private var checking = false
    /** Version already auto-downloaded or announced, so repeated checks stay quiet. */
    @Volatile private var handledVersion: String? = null

    fun register() {
        if (checked) return
        checked = true

        // /faupdate (and /fa update) always exist so anyone can check manually,
        // even with the automatic updater switched off.
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(literal("faupdate").executes { checkNow(); 1 })
        }

        // Respect the toggle — if disabled at startup, don't check or register the prompt listener.
        if (!FamilyConfigManager.config.general.autoUpdaterEnabled) {
            FamilyAddons.LOGGER.info("AutoUpdater: disabled in config, skipping check")
            return
        }

        CompletableFuture.runAsync {
            checkForUpdate()
            handleCheckResult()
        }

        // Periodic re-check while the game is running.
        ClientTickEvents.END_CLIENT_TICK.register {
            if (!FamilyConfigManager.config.general.autoUpdaterEnabled) return@register
            if (++recheckTicker < RECHECK_TICKS) return@register
            recheckTicker = 0
            if (checking || downloading) return@register
            checking = true
            CompletableFuture.runAsync {
                try {
                    checkForUpdate()
                    handleCheckResult()
                } finally {
                    checking = false
                }
            }
        }

        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen !is TitleScreen) return@register
            // Re-check the toggle at prompt time so users can toggle at runtime
            if (!FamilyConfigManager.config.general.autoUpdaterEnabled) return@register
            if (!updateAvailable) return@register
            if (downloaded) return@register
            if (downloading) return@register  // background download in flight
            if (AutoUpdater.skipped) return@register
            Minecraft.getInstance().execute {
                Minecraft.getInstance().gui.setScreen(UpdatePromptScreen(screen))
            }
        }

        // ── Chat notification only on a fresh server connect ──
        // Hypixel fires JOIN on every world transition (hub → island → dungeon, etc.),
        // so we gate on `freshConnect`: only fire if we just (re)connected from the
        // server list. Set back to false after firing so subsequent world hops don't
        // re-trigger; reset to true on DISCONNECT so the next real reconnect fires it.
        ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
            if (!FamilyConfigManager.config.general.autoUpdaterEnabled) return@register
            if (!updateAvailable) return@register
            if (downloading) return@register  // currently downloading (title-screen Yes or background)
            if (!freshConnect) return@register
            freshConnect = false
            if (downloaded) {
                // Already on disk (title-screen Yes or background download):
                // just remind once per connect that a restart applies it.
                CompletableFuture.runAsync {
                    Thread.sleep(1500)
                    Minecraft.getInstance().execute { sendDownloadedReminder() }
                }
                return@register
            }
            // Delay slightly so the notif appears AFTER Hypixel's join messages
            // (otherwise it gets buried under lobby spam).
            CompletableFuture.runAsync {
                Thread.sleep(1500)
                Minecraft.getInstance().execute { sendUpdateChatNotification() }
            }
        }

        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            freshConnect = true
        }
    }

    /**
     * Manual check — /fa update, /faupdate, and the chat notification click.
     * Always re-asks GitHub (the launch check may predate the release), then
     * either reports "up to date", reminds that a download is waiting for a
     * restart, or opens the Yes/No prompt.
     */
    fun checkNow() {
        val mc = Minecraft.getInstance()
        val player = mc.player
        if (checking) {
            player?.sendSystemMessage(Component.literal("§6[FA] §7Already checking for updates…"))
            return
        }
        if (downloading) {
            player?.sendSystemMessage(Component.literal("§6[FA] §7Already downloading an update…"))
            return
        }
        player?.sendSystemMessage(Component.literal("§6[FA] §7Checking GitHub for updates…"))
        checking = true
        CompletableFuture.runAsync {
            try {
                val ok = checkForUpdate()
                mc.execute {
                    val p = mc.player
                    when {
                        !ok -> p?.sendSystemMessage(Component.literal("§6[FA] §cCouldn't reach GitHub — try again in a bit."))
                        !updateAvailable -> p?.sendSystemMessage(Component.literal("§6[FA] §aYou're on the latest version (§e${FamilyAddons.VERSION}§a)."))
                        downloaded -> sendDownloadedReminder()
                        else -> {
                            handledVersion = latestVersion   // don't let the periodic check double up
                            triggerDownloadFromChat()
                        }
                    }
                }
            } finally {
                checking = false
            }
        }
    }

    /**
     * Runs after every GitHub check (launch + periodic). With "Auto Download
     * Updates" on, grabs the new jar silently so the next restart applies it;
     * otherwise announces it in chat right away instead of waiting for the next
     * launch. The title-screen prompt and the join-time chat notification stay
     * in place as the failsafe if this path fails or is disabled.
     */
    private fun handleCheckResult() {
        if (!updateAvailable) return
        val version = latestVersion ?: return
        if (version == handledVersion) return
        if (downloading) return
        handledVersion = version

        if (FamilyConfigManager.config.general.autoDownloadUpdates) {
            startBackgroundDownload(version)
        } else if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().execute { sendUpdateChatNotification() }
        }
    }

    private fun startBackgroundDownload(version: String) {
        FamilyAddons.LOGGER.info("AutoUpdater: auto-downloading $version in the background")
        startDownload { success ->
            val player = Minecraft.getInstance().player
            if (success) {
                player?.sendSystemMessage(Component.literal("§6[FA] §aFamilyAddons §e$version §adownloaded — it will be active after your next restart."))
            } else {
                // Let the next periodic check retry, and the launch prompt /
                // chat notification take over in the meantime.
                handledVersion = null
                player?.sendSystemMessage(Component.literal("§6[FA] §cBackground update download failed — will retry later, or run §f/faupdate§c."))
            }
        }
    }

    private fun sendDownloadedReminder() {
        val player = Minecraft.getInstance().player ?: return
        val latest = latestVersion ?: return
        player.sendSystemMessage(Component.literal("§6[FA] §aUpdate §e$latest §ais downloaded — restart Minecraft to apply it."))
    }

    private fun sendUpdateChatNotification() {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val latest = latestVersion ?: return
        val current = FamilyAddons.VERSION

        // Top divider
        player.sendSystemMessage(Component.literal("§8§m                                                  "))

        // Header
        player.sendSystemMessage(Component.literal("§6§lFamilyAddons §r§7» §eUpdate Available"))

        // Version line
        player.sendSystemMessage(Component.literal("§7Version §c$current §7→ §a$latest"))

        // Clickable action line
        val click: MutableComponent = (Component.literal("§8» §b§n[Click here to update]") as MutableComponent)
            .withStyle { s: Style ->
                s.withClickEvent(ClickEvent.RunCommand("/faupdate"))
                    .withHoverEvent(
                        HoverEvent.ShowText(
                            Component.literal(
                                "§eDownloads FamilyAddons §a$latest§e and removes the old jar.\n" +
                                        "§7You'll need to restart Minecraft after the download finishes."
                            )
                        )
                    )
            }
        player.sendSystemMessage(click)

        // Bottom divider
        player.sendSystemMessage(Component.literal("§8§m                                                  "))
    }

    /**
     * Called when the user clicks the chat notification (via /faupdate).
     * Opens a compact in-game version of the update prompt with release notes
     * and Yes/No buttons — same layout as the title-screen popup but smaller.
     */
    fun triggerDownloadFromChat() {
        val mc = Minecraft.getInstance()
        val player = mc.player

        if (!updateAvailable || downloadUrl == null) {
            player?.sendSystemMessage(Component.literal("§6[FA] §7No update available."))
            return
        }
        if (downloaded) {
            player?.sendSystemMessage(Component.literal("§6[FA] §aAlready downloaded — restart Minecraft to apply."))
            return
        }

        // Open the compact prompt on the main thread.
        mc.execute {
            mc.gui.setScreen(InGameUpdatePromptScreen())
        }
    }

    /**
     * Internal: actually performs the download and reports progress via chat.
     * Called by InGameUpdatePromptScreen after the user clicks "Yes".
     */
    internal fun performDownloadWithChatFeedback() {
        val mc = Minecraft.getInstance()
        val player = mc.player

        if (downloading) {
            player?.sendSystemMessage(Component.literal("§6[FA] §7Already downloading…"))
            return
        }

        player?.sendSystemMessage(Component.literal("§6[FA] §eDownloading FamilyAddons §a${latestVersion}§e…"))

        startDownload { success ->
            val p = Minecraft.getInstance().player ?: return@startDownload
            if (success) {
                p.sendSystemMessage(Component.literal("§6[FA] §aDownload complete!"))
                p.sendSystemMessage(Component.literal("§6[FA] §ePlease §lrestart Minecraft§r§e to apply the update."))
            } else {
                p.sendSystemMessage(Component.literal("§6[FA] §cDownload failed — check logs. Try again with §f/faupdate§c."))
            }
        }
    }

    /** Returns true if GitHub answered and the release info was parsed. */
    private fun checkForUpdate(): Boolean {
        try {
            val req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.github.com/repos/$GITHUB_REPO/releases/latest"))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "FamilyAddons/${FamilyAddons.VERSION}")
                .GET().build()
            val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
            val json = JsonParser.parseString(resp.body()).asJsonObject
            val tag = json.get("tag_name")?.asString?.trimStart('v') ?: return false
            val assets = json.getAsJsonArray("assets") ?: return false
            val asset = assets.firstOrNull {
                it.asJsonObject.get("name")?.asString?.endsWith(".jar") == true
            } ?: return false

            latestVersion = tag
            downloadUrl = asset.asJsonObject.get("browser_download_url")?.asString

            // Parse release notes from the body field
            val body = json.get("body")?.asString ?: ""
            releaseNotes = body.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .take(8) // cap at 8 lines so it fits the screen

            updateAvailable = isNewer(tag, FamilyAddons.VERSION)

            if (updateAvailable) {
                FamilyAddons.LOGGER.info("AutoUpdater: update available — $tag (you have ${FamilyAddons.VERSION})")
                FamilyAddons.LOGGER.info("AutoUpdater: download URL — $downloadUrl")
            } else {
                FamilyAddons.LOGGER.info("AutoUpdater: already up to date (${FamilyAddons.VERSION})")
            }
            return true
        } catch (e: Exception) {
            FamilyAddons.LOGGER.warn("AutoUpdater: check failed: ${e.message}")
            return false
        }
    }

    fun startDownload(onDone: (Boolean) -> Unit) {
        val url = downloadUrl ?: run { onDone(false); return }
        if (downloading) return
        downloading = true
        FamilyAddons.LOGGER.info("AutoUpdater: starting download of version $latestVersion")

        CompletableFuture.runAsync {
            try {
                val mc = Minecraft.getInstance()
                val modsDir = File(mc.gameDirectory, "mods")
                val newName = "FamilyAddons-${latestVersion}.jar"

                val tempFile = File(modsDir, "$newName.tmp")
                tempFile.delete()

                val req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "FamilyAddons/${FamilyAddons.VERSION}")
                    .GET().build()
                val resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream())
                FileOutputStream(tempFile).use { out -> resp.body().use { it.copyTo(out) } }

                if (!tempFile.exists() || tempFile.length() < 10_000) {
                    tempFile.delete()
                    throw Exception("Downloaded file is invalid (size: ${tempFile.length()} bytes)")
                }

                val outFile = File(modsDir, newName)
                outFile.delete()
                if (!tempFile.renameTo(outFile)) {
                    tempFile.copyTo(outFile, overwrite = true)
                    tempFile.delete()
                }
                FamilyAddons.LOGGER.info("AutoUpdater: downloaded $newName (${outFile.length() / 1024}KB)")

                val oldJars = modsDir.listFiles()?.filter {
                    it.name.startsWith("FamilyAddons") &&
                            it.name.endsWith(".jar") &&
                            it.absolutePath != outFile.absolutePath
                } ?: emptyList()

                if (oldJars.isNotEmpty()) {
                    val isWindows = System.getProperty("os.name", "").startsWith("Windows")

                    if (isWindows) {
                        val scriptFile = File(modsDir, "fa_update_cleanup.bat")
                        val sb = StringBuilder()
                        sb.appendLine("@echo off")
                        sb.appendLine(":waitloop")
                        for (f in oldJars) {
                            sb.appendLine("2>nul (>>\"${f.absolutePath}\" echo off) && goto :deletenow")
                        }
                        sb.appendLine("timeout /t 1 /nobreak >nul")
                        sb.appendLine("goto :waitloop")
                        sb.appendLine(":deletenow")
                        sb.appendLine("timeout /t 2 /nobreak >nul")
                        for (f in oldJars) {
                            sb.appendLine("del /f /q \"${f.absolutePath}\"")
                            sb.appendLine("if exist \"${f.absolutePath}\" del /f /q \"${f.absolutePath}\"")
                        }
                        sb.appendLine("del /f /q \"%~f0\"")
                        scriptFile.writeText(sb.toString())

                        Runtime.getRuntime().addShutdownHook(Thread {
                            try {
                                ProcessBuilder("cmd.exe", "/c", "start", "/min", "\"FA Cleanup\"", "/wait", "cmd.exe", "/c", scriptFile.absolutePath)
                                    .start()
                                FamilyAddons.LOGGER.info("AutoUpdater: cleanup script launched for ${oldJars.size} old jar(s)")
                            } catch (e: Exception) {
                                FamilyAddons.LOGGER.warn("AutoUpdater: failed to launch cleanup: ${e.message}")
                            }
                        })
                    } else {
                        oldJars.forEach { f ->
                            if (f.delete()) FamilyAddons.LOGGER.info("AutoUpdater: deleted ${f.name}")
                            else FamilyAddons.LOGGER.warn("AutoUpdater: could not delete ${f.name}")
                        }
                    }
                }

                downloaded = true
                downloading = false
                FamilyAddons.LOGGER.info("AutoUpdater: ready — restart Minecraft to apply update")
                mc.execute { onDone(true) }
            } catch (e: Exception) {
                FamilyAddons.LOGGER.warn("AutoUpdater: download failed: ${e.message}")
                downloading = false
                Minecraft.getInstance().execute { onDone(false) }
            }
        }
    }

    fun skip() { skipped = true }

    private fun isNewer(candidate: String, current: String): Boolean {
        return try {
            val c = candidate.split(".").map { it.toInt() }
            val v = current.split(".").map { it.toInt() }
            for (i in 0 until maxOf(c.size, v.size)) {
                val ci = c.getOrElse(i) { 0 }
                val vi = v.getOrElse(i) { 0 }
                if (ci > vi) return true
                if (ci < vi) return false
            }
            false
        } catch (e: Exception) { false }
    }
}

class UpdatePromptScreen(private val parent: Screen) : Screen(Component.literal("FamilyAddons Update")) {

    private var statusText: String? = null

    override fun init() {
        val centerX = width / 2
        val notes = AutoUpdater.releaseNotes
        // box height grows with number of note lines: base 110 + 10 per line
        val boxH = 110 + (notes.size * 10).coerceAtLeast(0)
        val boxY = height / 2 - boxH / 2

        addRenderableWidget(
            Button.builder(Component.literal("§aYes, update now")) {
                if (AutoUpdater.downloading) return@builder
                statusText = "§eDownloading..."
                AutoUpdater.startDownload { success ->
                    if (success) {
                        Minecraft.getInstance().gui.setScreen(parent)
                    } else {
                        statusText = "§cDownload failed — check logs. Click to retry."
                    }
                }
            }
                .bounds(centerX - 105, boxY + boxH - 30, 100, 20)
                .build()
        )

        addRenderableWidget(
            Button.builder(Component.literal("§cNo, skip")) {
                AutoUpdater.skip()
                Minecraft.getInstance().gui.setScreen(parent)
            }
                .bounds(centerX + 5, boxY + boxH - 30, 100, 20)
                .build()
        )
    }

    override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        context.fill(0, 0, width, height, 0xCC000000.toInt())

        val centerX = width / 2
        val notes = AutoUpdater.releaseNotes
        val boxW = 300
        val boxH = 110 + (notes.size * 10).coerceAtLeast(0)
        val boxY = height / 2 - boxH / 2
        val boxX = centerX - boxW / 2

        // Background + border
        context.fill(boxX, boxY, boxX + boxW, boxY + boxH, 0xEE1A1A2E.toInt())
        context.fill(boxX,        boxY,        boxX + boxW,     boxY + 1,        0xFF6C63FF.toInt())
        context.fill(boxX,        boxY + boxH, boxX + boxW,     boxY + boxH + 1, 0xFF6C63FF.toInt())
        context.fill(boxX,        boxY,        boxX + 1,        boxY + boxH,     0xFF6C63FF.toInt())
        context.fill(boxX + boxW, boxY,        boxX + boxW + 1, boxY + boxH,     0xFF6C63FF.toInt())

        val tr = font
        val latest = AutoUpdater.latestVersion ?: "?"

        // Title
        val title = "§e§lFamilyAddons Update Available"
        context.text(tr, title, centerX - tr.width(title.replace(Regex("§."), "")) / 2, boxY + 10, -1, true)

        // Version line
        val versionLine = "§fVersion §b$latest §7(MC ${FamilyAddons.MC_VERSION})"
        context.text(tr, versionLine, centerX - tr.width(versionLine.replace(Regex("§."), "")) / 2, boxY + 24, -1, true)

        // Divider
        context.fill(boxX + 10, boxY + 36, boxX + boxW - 10, boxY + 37, 0x886C63FF.toInt())

        // Release notes
        if (notes.isNotEmpty()) {
            val notesLabel = "§7What's new:"
            context.text(tr, notesLabel, boxX + 10, boxY + 42, -1, false)
            notes.forEachIndexed { i, line ->
                // Strip markdown bold (**text**) and format as plain
                val clean = line.removePrefix("- ").removePrefix("* ")
                    .replace("**", "")
                val display = "§f- §7$clean"
                // Truncate if too wide for box
                val maxW = boxW - 20
                val truncated = if (tr.width(display.replace(Regex("§."), "")) > maxW) {
                    var t = display
                    while (tr.width(t.replace(Regex("§."), "") + "...") > maxW && t.length > 4)
                        t = t.dropLast(1)
                    "$t..."
                } else display
                context.text(tr, truncated, boxX + 10, boxY + 54 + i * 10, -1, false)
            }
        } else {
            val noNotes = "§7No release notes available."
            context.text(tr, noNotes, centerX - tr.width(noNotes.replace(Regex("§."), "")) / 2, boxY + 44, -1, false)
        }

        // Status text (downloading / error)
        val status = statusText
        if (status != null) {
            context.text(tr, status, centerX - tr.width(status.replace(Regex("§."), "")) / 2, boxY + boxH + 6, -1, true)
        }

        super.extractRenderState(context, mouseX, mouseY, delta)
    }

    override fun shouldCloseOnEsc() = false
}

/**
 * In-game variant of UpdatePromptScreen — same Yes/No layout, release notes,
 * and dimensions as the title-screen popup. Closing returns to gameplay.
 */
class InGameUpdatePromptScreen : Screen(Component.literal("FamilyAddons Update")) {

    private var statusText: String? = null

    override fun init() {
        val centerX = width / 2
        val notes = AutoUpdater.releaseNotes
        // Match UpdatePromptScreen: base 110 + 10 per note line
        val boxH = 110 + (notes.size * 10).coerceAtLeast(0)
        val boxY = height / 2 - boxH / 2

        addRenderableWidget(
            Button.builder(Component.literal("§aYes, update now")) {
                if (AutoUpdater.downloading) return@builder
                Minecraft.getInstance().gui.setScreen(null) // back to game
                AutoUpdater.performDownloadWithChatFeedback()
            }
                .bounds(centerX - 105, boxY + boxH - 30, 100, 20)
                .build()
        )

        addRenderableWidget(
            Button.builder(Component.literal("§cNo, skip")) {
                Minecraft.getInstance().gui.setScreen(null) // back to game
            }
                .bounds(centerX + 5, boxY + boxH - 30, 100, 20)
                .build()
        )
    }

    override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        // Slightly lighter dim than the title-screen version so the world stays visible
        context.fill(0, 0, width, height, 0x99000000.toInt())

        val centerX = width / 2
        val notes = AutoUpdater.releaseNotes
        val boxW = 300
        val boxH = 110 + (notes.size * 10).coerceAtLeast(0)
        val boxY = height / 2 - boxH / 2
        val boxX = centerX - boxW / 2

        // Background + border
        context.fill(boxX, boxY, boxX + boxW, boxY + boxH, 0xEE1A1A2E.toInt())
        context.fill(boxX,        boxY,        boxX + boxW,     boxY + 1,        0xFF6C63FF.toInt())
        context.fill(boxX,        boxY + boxH, boxX + boxW,     boxY + boxH + 1, 0xFF6C63FF.toInt())
        context.fill(boxX,        boxY,        boxX + 1,        boxY + boxH,     0xFF6C63FF.toInt())
        context.fill(boxX + boxW, boxY,        boxX + boxW + 1, boxY + boxH,     0xFF6C63FF.toInt())

        val tr = font
        val latest = AutoUpdater.latestVersion ?: "?"

        // Title
        val title = "§e§lFamilyAddons Update Available"
        context.text(tr, title, centerX - tr.width(title.replace(Regex("§."), "")) / 2, boxY + 10, -1, true)

        // Version line
        val versionLine = "§fVersion §b$latest §7(MC ${FamilyAddons.MC_VERSION})"
        context.text(tr, versionLine, centerX - tr.width(versionLine.replace(Regex("§."), "")) / 2, boxY + 24, -1, true)

        // Divider
        context.fill(boxX + 10, boxY + 36, boxX + boxW - 10, boxY + 37, 0x886C63FF.toInt())

        // Release notes
        if (notes.isNotEmpty()) {
            val notesLabel = "§7What's new:"
            context.text(tr, notesLabel, boxX + 10, boxY + 42, -1, false)
            notes.forEachIndexed { i, line ->
                val clean = line.removePrefix("- ").removePrefix("* ").replace("**", "")
                val display = "§f- §7$clean"
                val maxW = boxW - 20
                val truncated = if (tr.width(display.replace(Regex("§."), "")) > maxW) {
                    var t = display
                    while (tr.width(t.replace(Regex("§."), "") + "...") > maxW && t.length > 4)
                        t = t.dropLast(1)
                    "$t..."
                } else display
                context.text(tr, truncated, boxX + 10, boxY + 54 + i * 10, -1, false)
            }
        } else {
            val noNotes = "§7No release notes available."
            context.text(tr, noNotes, centerX - tr.width(noNotes.replace(Regex("§."), "")) / 2, boxY + 44, -1, false)
        }

        // Status text under the box (downloading / error)
        val status = statusText
        if (status != null) {
            context.text(tr, status, centerX - tr.width(status.replace(Regex("§."), "")) / 2, boxY + boxH + 6, -1, true)
        }

        super.extractRenderState(context, mouseX, mouseY, delta)
    }

    // Allow ESC to dismiss — unlike the title-screen version, this is mid-game.
    override fun shouldCloseOnEsc() = true
}