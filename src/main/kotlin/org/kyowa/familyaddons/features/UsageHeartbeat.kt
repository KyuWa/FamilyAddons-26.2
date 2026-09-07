package org.kyowa.familyaddons.features

import com.google.gson.JsonParser
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import org.kyowa.familyaddons.FamilyAddons
import org.kyowa.familyaddons.KeyFetcher
import org.kyowa.familyaddons.config.FamilyConfigManager
import org.kyowa.familyaddons.util.FaChat
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * Usage heartbeat: tells the presence worker that this account is running the
 * mod — on every server join and once a minute after that. Payload is the
 * player's UUID, name, mod version and Minecraft version, nothing else.
 *
 * The owner reads the result with /fa users and /fa online (DevAccess-gated)
 * using a separate admin key that is typed into the config, never shipped.
 */
object UsageHeartbeat {

    private const val WORKER_URL = "https://fa-presence.220395610.workers.dev"
    private const val BEAT_INTERVAL_TICKS = 20 * 60

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private var ticker = 0
    @Volatile private var inFlight = false

    fun register() {
        ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
            ticker = 0
            beat()
        }
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (client.player == null) return@register
            if (++ticker < BEAT_INTERVAL_TICKS) return@register
            ticker = 0
            beat()
        }
    }

    private fun beat() {
        if (inFlight) return
        val user = Minecraft.getInstance().user ?: return
        val uuid = user.profileId?.toString() ?: return
        val name = user.name ?: return
        inFlight = true
        CompletableFuture.runAsync {
            try {
                val body = """{"uuid":"$uuid","name":"$name","version":"${FamilyAddons.VERSION}","mc":"${FamilyAddons.MC_VERSION}"}"""
                val req = HttpRequest.newBuilder()
                    .uri(URI.create("$WORKER_URL/beat"))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .header(KeyFetcher.SECRET_HEADER, KeyFetcher.SECRET_TOKEN)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build()
                http.send(req, HttpResponse.BodyHandlers.discarding())
            } catch (e: Exception) {
                FamilyAddons.LOGGER.debug("UsageHeartbeat: beat failed: ${e.message}")
            } finally {
                inFlight = false
            }
        }
    }

    /** /fa users | /fa online — owner view of who runs the mod. */
    fun showUsers(onlineOnly: Boolean) {
        val key = FamilyConfigManager.config.general.presenceAdminKey.trim()
        if (key.isEmpty()) { chat("§cNo usage stats key set. Paste it in /fa > General > Usage Stats Key."); return }
        val path = if (onlineOnly) "/online" else "/users"
        chat("§7Fetching ${if (onlineOnly) "online players" else "all users"}...")
        CompletableFuture.runAsync {
            try {
                val req = HttpRequest.newBuilder()
                    .uri(URI.create("$WORKER_URL$path"))
                    .timeout(Duration.ofSeconds(10))
                    .header("X-Admin-Key", key)
                    .GET().build()
                val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
                if (resp.statusCode() == 401) { chat("§cWrong usage stats key."); return@runAsync }
                if (resp.statusCode() != 200) { chat("§cStats request failed: HTTP ${resp.statusCode()}"); return@runAsync }
                val root = JsonParser.parseString(resp.body()).asJsonObject
                val now = root.get("now").asLong
                val users = root.getAsJsonArray("users")
                val online = root.get("online").asInt
                chat("§6Mod users: §f${users.size()}§7 listed, §a$online§7 online now")
                for (u in users) {
                    val o = u.asJsonObject
                    val name = o.get("name").asString
                    val ver = o.get("version")?.asString ?: "?"
                    val mc = o.get("mc")?.asString ?: "?"
                    val last = o.get("last").asLong
                    val isOnline = o.get("online")?.asBoolean == true
                    val status = if (isOnline) "§aonline" else "§7last seen ${ago(now - last)} ago"
                    chat("  §f$name §8[§b$ver §8/ §b$mc§8] $status")
                }
            } catch (e: Exception) {
                chat("§cStats request failed: ${e.message}")
            }
        }
    }

    private fun ago(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m"
            s < 86400 -> "${s / 3600}h"
            else -> "${s / 86400}d"
        }
    }

    private fun chat(msg: String) {
        Minecraft.getInstance().execute {
            Minecraft.getInstance().player?.sendSystemMessage(FaChat.prefixed(msg))
        }
    }
}
