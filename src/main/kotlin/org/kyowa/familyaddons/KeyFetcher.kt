package org.kyowa.familyaddons

import org.kyowa.familyaddons.config.FamilyConfigManager
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

object KeyFetcher {

    private const val WORKER_URL = "https://key.kyowa.uk/"
    private const val SECRET_HEADER = "X-FA-Secret"
    private const val SECRET_TOKEN = "41d050ef-7801-47bd-880e-f0e052a3bbc3"

    private val client = HttpClient.newHttpClient()
    private var fetchedKey: String? = null
    private var fetchAttempted = false

    // Returns user's manual key if set, otherwise the fetched key
    fun getApiKey(): String? {
        val configKey = FamilyConfigManager.config.general.hypixelApiKey.takeIf { it.isNotBlank() }
        if (configKey != null) return configKey

        // If not fetched yet, try fetching synchronously as fallback
        if (!fetchAttempted) fetchNow()
        return fetchedKey
    }

    fun fetchIfNeeded() {
        Thread {
            fetchNow()
        }.also { it.isDaemon = true }.start()
    }

    private fun fetchNow() {
        fetchAttempted = true
        try {
            val request = HttpRequest.newBuilder()
                .uri(URI(WORKER_URL))
                .header(SECRET_HEADER, SECRET_TOKEN)
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) {
                val json = com.google.gson.JsonParser.parseString(response.body()).asJsonObject
                val key = json.get("hypixel_api_key")?.asString
                if (!key.isNullOrBlank()) {
                    fetchedKey = key
                    FamilyAddons.LOGGER.info("KeyFetcher: key loaded successfully")
                } else {
                    FamilyAddons.LOGGER.warn("KeyFetcher: response had no key")
                }
            } else {
                FamilyAddons.LOGGER.warn("KeyFetcher: HTTP ${response.statusCode()} — ${response.body()}")
            }
        } catch (e: Exception) {
            FamilyAddons.LOGGER.warn("KeyFetcher failed: ${e.message}")
        }
    }
}
