package org.kyowa.familyaddons.features

import com.google.gson.JsonParser
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import org.kyowa.familyaddons.FamilyAddons
import org.kyowa.familyaddons.config.FamilyConfigManager
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture

object SharedDisguiseSync {

    private const val WORKER_URL = "https://disguise.kyowa.uk"
    private const val SECRET = "kyowa-fa-secret-2025"

    /**
     * customScale defaults to 1.0f for backwards compatibility — disguises pushed
     * by older clients (or by the worker before it knew about customScale) won't
     * have the field, and we treat that as "no scale change".
     */
    data class SyncedDisguise(
        val mobId: String,
        val baby: Boolean,
        val sheared: Boolean = false,
        val customScale: Float = 1.0f
    )

    @Volatile
    var remoteDisguises: Map<String, SyncedDisguise> = emptyMap()
        private set

    private val http = HttpClient.newHttpClient()
    private var tickCounter = 0

    private var lastPushedEnabled: Boolean? = null
    private var lastPushedMobId: String? = null
    private var lastPushedBaby: Boolean? = null
    private var lastPushedSheared: Boolean? = null
    private var lastPushedScale: Float? = null

    // ── Push own disguise ─────────────────────────────────────────────
    fun pushMyDisguise() {
        val cfg = FamilyConfigManager.config.playerDisguise
        if (!cfg.enabled) { deleteMyDisguise(); return }
        val username = Minecraft.getInstance().player?.name?.string
            ?: Minecraft.getInstance().user.name
            ?: return

        // Compute effective scale: 1.0 if scaling is off, else the clamped slider value (in tenths) divided by 10.
        val effectiveScale = if (cfg.customScalingEnabled)
            (cfg.customScaleTenths / 10f).coerceIn(0.1f, 5.0f)
        else 1.0f

        CompletableFuture.runAsync {
            try {
                val body = """{"username":"$username","mobId":"${cfg.mobId}","baby":${cfg.baby},"sheared":${cfg.sheared},"customScale":$effectiveScale}"""
                val req = HttpRequest.newBuilder()
                    .uri(URI.create("$WORKER_URL/disguise"))
                    .header("Content-Type", "application/json")
                    .header("X-FA-Secret", SECRET)
                    .PUT(HttpRequest.BodyPublishers.ofString(body))
                    .build()
                http.send(req, HttpResponse.BodyHandlers.ofString())
                FamilyAddons.LOGGER.info("SharedDisguiseSync: pushed $username → ${cfg.mobId} (scale=$effectiveScale)")
            } catch (e: Exception) {
                FamilyAddons.LOGGER.warn("SharedDisguiseSync: push failed: ${e.message}")
            }
        }
    }

    // ── Delete own disguise ───────────────────────────────────────────
    fun deleteMyDisguise() {
        val username = Minecraft.getInstance().player?.name?.string
            ?: Minecraft.getInstance().user.name
            ?: return
        CompletableFuture.runAsync {
            try {
                val body = """{"username":"$username"}"""
                val req = HttpRequest.newBuilder()
                    .uri(URI.create("$WORKER_URL/disguise"))
                    .header("Content-Type", "application/json")
                    .header("X-FA-Secret", SECRET)
                    .method("DELETE", HttpRequest.BodyPublishers.ofString(body))
                    .build()
                http.send(req, HttpResponse.BodyHandlers.ofString())
            } catch (e: Exception) {
                FamilyAddons.LOGGER.warn("SharedDisguiseSync: delete failed: ${e.message}")
            }
        }
    }

    // ── Fetch all — only on game launch + manual refresh button ──────
    fun fetchAllNow() { fetchAll() }

    private fun fetchAll() {
        CompletableFuture.runAsync {
            try {
                val req = HttpRequest.newBuilder()
                    .uri(URI.create("$WORKER_URL/disguise/all"))
                    .GET().build()
                val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
                val json = JsonParser.parseString(resp.body()).asJsonObject
                val result = mutableMapOf<String, SyncedDisguise>()
                for ((name, entry) in json.entrySet()) {
                    val obj = entry.asJsonObject
                    val mobId = obj.get("mobId")?.asString ?: continue
                    val baby = obj.get("baby")?.asBoolean ?: false
                    val sheared = obj.get("sheared")?.asBoolean ?: false
                    // Default to 1.0 if the worker hasn't been updated yet or the
                    // disguise was pushed by a pre-customScale client.
                    val customScale = obj.get("customScale")?.asFloat ?: 1.0f
                    result[name.lowercase()] = SyncedDisguise(mobId, baby, sheared, customScale)
                }
                remoteDisguises = result
                FamilyAddons.LOGGER.info("SharedDisguiseSync: fetched ${result.size} disguises")
            } catch (e: Exception) {
                FamilyAddons.LOGGER.warn("SharedDisguiseSync: fetch failed: ${e.message}")
            }
        }
    }

    private fun resetPushState() {
        lastPushedEnabled = null
        lastPushedMobId = null
        lastPushedBaby = null
        lastPushedSheared = null
        lastPushedScale = null
    }

    fun register() {
        // Fetch once on game launch
        fetchAll()

        // Only push when config changes — no polling fetch
        ClientTickEvents.END_CLIENT_TICK.register { _ ->
            if (tickCounter++ % 20 != 0) return@register
            val cfg = FamilyConfigManager.config.playerDisguise
            // Compute the effective scale we'd push so the diff matches what's stored remotely.
            val effectiveScale = if (cfg.customScalingEnabled)
                (cfg.customScaleTenths / 10f).coerceIn(0.1f, 5.0f)
            else 1.0f
            val changed = cfg.enabled != lastPushedEnabled ||
                    cfg.mobId != lastPushedMobId ||
                    cfg.baby != lastPushedBaby ||
                    cfg.sheared != lastPushedSheared ||
                    effectiveScale != lastPushedScale
            if (changed) {
                lastPushedEnabled = cfg.enabled
                lastPushedMobId = cfg.mobId
                lastPushedBaby = cfg.baby
                lastPushedSheared = cfg.sheared
                lastPushedScale = effectiveScale
                if (cfg.enabled) pushMyDisguise() else deleteMyDisguise()
            }
        }

        // Clear cache on disconnect, re-push own disguise on next join
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> resetPushState() }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            remoteDisguises = emptyMap()
            resetPushState()
        }
    }

    fun getDisguise(username: String): SyncedDisguise? {
        if (!FamilyConfigManager.config.playerDisguise.showFriendsDisguises) return null
        return remoteDisguises[username.lowercase()]
    }
}