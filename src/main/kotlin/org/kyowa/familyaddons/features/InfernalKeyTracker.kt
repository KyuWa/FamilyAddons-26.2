package org.kyowa.familyaddons.features

import org.kyowa.familyaddons.COLOR_CODE_REGEX
import org.kyowa.familyaddons.util.FaChat
import com.google.gson.JsonParser
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import org.kyowa.familyaddons.FamilyAddons
import org.kyowa.familyaddons.KeyFetcher
import org.kyowa.familyaddons.config.FamilyConfigManager
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture

object InfernalKeyTracker {

    private val httpClient = HttpClient.newHttpClient()

    private const val MYCELIUM_PER_INFERNAL = 80
    private const val REDSAND_PER_INFERNAL  = 80
    private const val STARS_PER_INFERNAL    = 2
    private const val COINS_PER_INFERNAL    = 2_328_000L
    private const val HUD_W = 166
    private const val HUD_H = 71

    private val TIER_REDSAND = mapOf(
        "Kuudra Key"          to 2,
        "Hot Kuudra Key"      to 4,
        "Burning Kuudra Key"  to 16,
        "Fiery Kuudra Key"    to 40,
        "Infernal Kuudra Key" to 80
    )
    private val TIER_STARS = mapOf(
        "Kuudra Key"          to 2,
        "Hot Kuudra Key"      to 2,
        "Burning Kuudra Key"  to 2,
        "Fiery Kuudra Key"    to 2,
        "Infernal Kuudra Key" to 2
    )

    var hudX: Int
        get() = FamilyConfigManager.config.kuudra.keyTrackerHudX
        set(value) { FamilyConfigManager.config.kuudra.keyTrackerHudX = value }
    var hudY: Int
        get() = FamilyConfigManager.config.kuudra.keyTrackerHudY
        set(value) { FamilyConfigManager.config.kuudra.keyTrackerHudY = value }

    private var mycelium  = 0
    private var redSand   = 0
    private var stars     = 0
    private var lastFetch: String? = null
    private var fetching  = false

    fun register() {
        var joinTimer = 0
        ClientTickEvents.END_CLIENT_TICK.register {
            if (joinTimer > 0) {
                joinTimer--
                if (joinTimer == 0) fetchSacks(silent = true)
            }
        }

        ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
            joinTimer = 60
        }

        ClientReceiveMessageEvents.ALLOW_GAME.register { message, _ ->
            if (FamilyConfigManager.config.kuudra.keyTracker) {
                val plain = message.string.replace(COLOR_CODE_REGEX, "").trim()
                if (plain.startsWith("You bought ") && plain.endsWith("!")) {
                    val keyName = plain.removePrefix("You bought ").removeSuffix("!")
                    val rsAmt = TIER_REDSAND[keyName]
                    val stAmt = TIER_STARS[keyName]
                    if (rsAmt != null) {
                        val shopName = getContainerName() ?: ""
                        if (keyName == "Infernal Kuudra Key") {
                            if (shopName.contains("mage shop", ignoreCase = true)) {
                                mycelium = maxOf(0, mycelium - MYCELIUM_PER_INFERNAL)
                            } else {
                                redSand = maxOf(0, redSand - REDSAND_PER_INFERNAL)
                            }
                        } else {
                            redSand = maxOf(0, redSand - rsAmt)
                        }
                        if (stAmt != null) stars = maxOf(0, stars - stAmt)
                    }
                }
            }
            true
        }

        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            ScreenEvents.afterExtract(screen).register { _, context, _, _, _ ->
                if (!FamilyConfigManager.config.kuudra.keyTracker) return@register
                if (!isInShop() && screen !is KeyTrackerMoveScreen) return@register
                val isMoving = screen is KeyTrackerMoveScreen
                drawHud(context, isMoving)
            }
        }
    }

    fun openMoveScreen() {
        Minecraft.getInstance().gui.setScreen(KeyTrackerMoveScreen())
    }

    private fun drawHud(context: GuiGraphicsExtractor, showHint: Boolean = false) {
        val keysMyc   = mycelium / MYCELIUM_PER_INFERNAL
        val keysRed   = redSand  / REDSAND_PER_INFERNAL
        val keysStars = stars    / STARS_PER_INFERNAL
        val maxKeys   = minOf(keysMyc + keysRed, keysStars)
        val coins     = formatShort(maxKeys.toLong() * COINS_PER_INFERNAL)

        val tr = Minecraft.getInstance().font
        val lh = 10
        var y = hudY

        context.fill(hudX - 3, hudY - 3, hudX + HUD_W, hudY + HUD_H, -1275068416.toInt())
        if (showHint) {
            context.fill(hudX - 3, hudY - 3, hudX + HUD_W, hudY - 2, -1)
            context.fill(hudX - 3, hudY + HUD_H, hudX + HUD_W, hudY + HUD_H + 1, -1)
            context.fill(hudX - 3, hudY - 3, hudX - 2, hudY + HUD_H, -1)
            context.fill(hudX + HUD_W - 1, hudY - 3, hudX + HUD_W, hudY + HUD_H, -1)
        }
        context.text(tr, "§5§lInfernal Key Tracker", hudX, y, -1, true); y += lh + 2
        context.text(tr, "§bMycelium§7: §f$mycelium §7(§f$keysMyc§7 keys)", hudX, y, -1, true); y += lh
        context.text(tr, "§bRed Sand§7: §f$redSand §7(§f$keysRed§7 keys)", hudX, y, -1, true); y += lh
        context.text(tr, "§bNether Stars§7: §f$stars §7(§f$keysStars§7 keys)", hudX, y, -1, true); y += lh
        context.text(tr, "§aMax Keys§7: §f$maxKeys §7(§6$coins§7)", hudX, y, -1, true); y += lh
        if (showHint) {
            context.text(tr, "§eDrag to move, Esc to save", hudX, y, -1, true)
        } else {
            lastFetch?.let { context.text(tr, "§8Updated: $it", hudX, y, -1, true) }
        }
    }

    fun getLineCount() = 6  // title + myc + red + stars + max + updated

    fun renderLines(context: GuiGraphicsExtractor) {
        val keysMyc   = mycelium / MYCELIUM_PER_INFERNAL
        val keysRed   = redSand  / REDSAND_PER_INFERNAL
        val keysStars = stars    / STARS_PER_INFERNAL
        val maxKeys   = minOf(keysMyc + keysRed, keysStars)
        val coins     = formatShort(maxKeys.toLong() * COINS_PER_INFERNAL)
        val tr = Minecraft.getInstance().font
        val lh = 10
        var y = 0
        context.text(tr, "§5§lInfernal Key Tracker", 0, y, -1, true); y += lh + 2
        context.text(tr, "§bMycelium§7: §f §7(§f§7 keys)", 0, y, -1, true); y += lh
        context.text(tr, "§bRed Sand§7: §f §7(§f§7 keys)", 0, y, -1, true); y += lh
        context.text(tr, "§bNether Stars§7: §f §7(§f§7 keys)", 0, y, -1, true); y += lh
        context.text(tr, "§aMax Keys§7: §f §7(§6§7)", 0, y, -1, true)
    }

    class KeyTrackerMoveScreen : Screen(Component.literal("Key Tracker Position")) {

        private var dragging = false
        private var dragOffX = 0.0
        private var dragOffY = 0.0

        override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
            context.fill(0, 0, width, height, -1873784752)
            super.extractRenderState(context, mouseX, mouseY, delta)
        }

        override fun mouseMoved(mouseX: Double, mouseY: Double) {
            if (dragging) {
                hudX = (mouseX - dragOffX).toInt().coerceIn(0, width - HUD_W)
                hudY = (mouseY - dragOffY).toInt().coerceIn(0, height - HUD_H)
            }
        }

        // Use Screen's existing input handling via tick-based mouse button check
        override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
        }

        fun onMousePress(mouseX: Double, mouseY: Double) {
            if (mouseX >= hudX - 3 && mouseX <= hudX + HUD_W &&
                mouseY >= hudY - 3 && mouseY <= hudY + HUD_H) {
                dragging = true
                dragOffX = mouseX - hudX
                dragOffY = mouseY - hudY
            }
        }

        fun onMouseRelease() {
            dragging = false
        }

        override fun onClose() {
            FamilyConfigManager.config.kuudra.keyTrackerHudX = hudX
            FamilyConfigManager.config.kuudra.keyTrackerHudY = hudY
            FamilyConfigManager.save()
            super.onClose()
        }

        override fun isPauseScreen() = false
    }

    // Track mouse state via tick for the move screen
    var openScreenNextTick = false
    private var moveScreenRef: KeyTrackerMoveScreen? = null
    private var wasMouseDown = false

    init {
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (openScreenNextTick) {
                openScreenNextTick = false
                client.gui.setScreen(KeyTrackerMoveScreen())
                return@register
            }
            val screen = client.gui.screen() as? KeyTrackerMoveScreen ?: run {
                moveScreenRef = null
                wasMouseDown = false
                return@register
            }
            moveScreenRef = screen

            val mouseDown = org.lwjgl.glfw.GLFW.glfwGetMouseButton(
                client.window.handle(),
                org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT
            ) == org.lwjgl.glfw.GLFW.GLFW_PRESS

            val mx = client.mouseHandler.getScaledXPos(client.window)
            val my = client.mouseHandler.getScaledYPos(client.window)

            if (mouseDown && !wasMouseDown) {
                screen.onMousePress(mx, my)
            } else if (!mouseDown && wasMouseDown) {
                screen.onMouseRelease()
            }
            wasMouseDown = mouseDown
        }
    }

    fun fetchSacks(silent: Boolean) {
        if (fetching) return
        val apiKey = KeyFetcher.getApiKey()
        if (apiKey.isNullOrBlank()) {
            if (!silent) chat("§cNo API key set. Set it in /fa > General.")
            return
        }

        fetching = true
        CompletableFuture.runAsync {
            try {
                val client = Minecraft.getInstance()
                val ign = client.player?.name?.string ?: run { fetching = false; return@runAsync }

                val mojang = get("https://api.mojang.com/users/profiles/minecraft/$ign")
                val uuid = mojang?.get("id")?.asString ?: run {
                    if (!silent) chat("§cUUID lookup failed.")
                    fetching = false; return@runAsync
                }

                val data = get("https://api.hypixel.net/v2/skyblock/profiles?uuid=$uuid&key=$apiKey")
                if (data?.get("success")?.asBoolean != true) {
                    if (!silent) chat("§cAPI error.")
                    fetching = false; return@runAsync
                }

                val profiles = data.getAsJsonArray("profiles")
                val profile = profiles?.map { it.asJsonObject }
                    ?.firstOrNull { it.get("selected")?.asBoolean == true }
                    ?: profiles?.lastOrNull()?.asJsonObject
                    ?: run { if (!silent) chat("§cNo profile."); fetching = false; return@runAsync }

                val member = profile.getAsJsonObject("members")?.getAsJsonObject(uuid)
                    ?: run { if (!silent) chat("§cNo member data."); fetching = false; return@runAsync }

                val sacks = member.getAsJsonObject("inventory")?.getAsJsonObject("sacks_counts")
                    ?: run { if (!silent) chat("§cNo sack data."); fetching = false; return@runAsync }

                mycelium = sacks.get("ENCHANTED_MYCELIUM")?.asInt    ?: 0
                redSand  = sacks.get("ENCHANTED_RED_SAND")?.asInt    ?: 0
                stars    = sacks.get("CORRUPTED_NETHER_STAR")?.asInt  ?: 0
                lastFetch = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))

                if (!silent) chat("§7Updated — §bMyc: §f$mycelium §7| §bRed: §f$redSand §7| §bStars: §f$stars")
            } catch (e: Exception) {
                FamilyAddons.LOGGER.warn("InfernalKeyTracker error: ${e.message}")
                if (!silent) chat("§cFetch error: ${e.message}")
            }
            fetching = false
        }
    }

    private fun get(url: String) = try {
        val req = HttpRequest.newBuilder().uri(URI.create(url))
            .header("User-Agent", "FamilyAddons/1.0").GET().build()
        val res = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
        JsonParser.parseString(res.body()).asJsonObject
    } catch (e: Exception) { null }

    private fun getContainerName(): String? {
        return try {
            Minecraft.getInstance().gui.screen()?.title?.string
                ?.replace(COLOR_CODE_REGEX, "")?.lowercase()
        } catch (e: Exception) { null }
    }

    private fun isInShop(): Boolean {
        val name = getContainerName() ?: return false
        return name.contains("mage shop") || name.contains("barbarian shop")
    }

    private fun formatShort(n: Long): String {
        return when {
            n >= 1_000_000_000 -> "%.1fb".format(n / 1_000_000_000.0).replace(".0b", "b")
            n >= 1_000_000     -> "%.1fm".format(n / 1_000_000.0).replace(".0m", "m")
            n >= 1_000         -> "%.1fk".format(n / 1_000.0).replace(".0k", "k")
            else               -> n.toString()
        }
    }

    private fun chat(msg: String) {
        Minecraft.getInstance().execute {
            Minecraft.getInstance().player?.sendSystemMessage(FaChat.prefixed("$msg"))
        }
    }
}
