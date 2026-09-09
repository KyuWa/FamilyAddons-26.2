package org.kyowa.familyaddons

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.kyowa.familyaddons.config.FamilyConfigManager
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Hypixel API access without shipping a key.
 *
 * The key never leaves Cloudflare: the mod asks the key worker for the data
 * it needs ("profiles for this uuid") and the worker makes the Hypixel call
 * with the key it holds as a secret, caches the answer briefly and rate-limits
 * per uuid. A player who set their own key under General > Hypixel API Key
 * bypasses the worker and calls Hypixel directly with it.
 *
 * [SECRET_TOKEN] is the shared gate for all the mod's workers (key, translate,
 * presence). It is in the jar, so it is a speed bump, not a secret: the worker
 * side rate limits and never returns anything sensitive.
 */
object KeyFetcher {

    private const val WORKER_URL = "https://key.kyowa.uk"
    const val SECRET_HEADER = "X-FA-Secret"
    const val SECRET_TOKEN = "41d050ef-7801-47bd-880e-f0e052a3bbc3"

    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build()

    /** The player's own key from the config, or null. No fetching: keys are not handed out any more. */
    fun getApiKey(): String? = FamilyConfigManager.config.general.hypixelApiKey.takeIf { it.isNotBlank() }

    /** Kept for callers that used to warm the cache; nothing to prefetch now. */
    fun fetchIfNeeded() {}

    /**
     * `/v2/skyblock/profiles` for [uuid] (hex, with or without dashes), as
     * Hypixel returns it, or null on any failure. Own key → direct; else the
     * worker proxy. Blocking: call off the render thread.
     */
    fun fetchProfiles(uuid: String): JsonObject? {
        val clean = uuid.replace("-", "").lowercase()
        val own = getApiKey()
        return try {
            val req = if (own != null) {
                HttpRequest.newBuilder()
                    .uri(URI.create("https://api.hypixel.net/v2/skyblock/profiles?uuid=$clean"))
                    .header("API-Key", own)
                    .header("User-Agent", "FamilyAddons/${FamilyAddons.VERSION}")
                    .timeout(Duration.ofSeconds(15)).GET().build()
            } else {
                HttpRequest.newBuilder()
                    .uri(URI.create("$WORKER_URL/profiles?uuid=$clean"))
                    .header(SECRET_HEADER, SECRET_TOKEN)
                    .header("User-Agent", "FamilyAddons/${FamilyAddons.VERSION}")
                    .timeout(Duration.ofSeconds(15)).GET().build()
            }
            val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() != 200) {
                FamilyAddons.LOGGER.warn("KeyFetcher: profiles HTTP ${resp.statusCode()} ${resp.body().take(200)}")
                return null
            }
            JsonParser.parseString(resp.body()).asJsonObject
        } catch (e: Exception) {
            FamilyAddons.LOGGER.warn("KeyFetcher: profiles failed: ${e.message}")
            null
        }
    }
}
