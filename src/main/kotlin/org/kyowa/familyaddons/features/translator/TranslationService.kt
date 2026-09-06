package org.kyowa.familyaddons.features.translator

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import org.kyowa.familyaddons.FamilyAddons
import org.kyowa.familyaddons.KeyFetcher
import org.kyowa.familyaddons.config.FamilyConfigManager
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs translations off the render thread, with the slang passes wrapped
 * around whichever backend is configured.
 *
 * Chat arrives in bursts, so everything here is defensive: results are cached,
 * requests are rate limited, the queue is bounded and drops rather than
 * backing up, and a 429 parks the whole feature for a few minutes instead of
 * hammering the endpoint.
 */
object TranslationService {

    data class Result(
        val text: String,
        /** Source language reported by the backend, e.g. "es". */
        val detected: String,
        /** True when the line was mostly slang the dictionary did not know. */
        val uncertain: Boolean,
        /** Deep mode only: the model's explanation of the slang it found, or null. */
        val notes: String? = null,
    )

    /** What a backend hands back before the dictionary post-pass. */
    private data class BackendResult(val text: String, val detected: String, val notes: String? = null)

    private const val CACHE_SIZE = 300
    private const val MAX_QUEUE = 16
    private const val REQUESTS_PER_WINDOW = 30
    private val WINDOW_MS = TimeUnit.SECONDS.toMillis(60)
    private val BACKOFF_MS = TimeUnit.MINUTES.toMillis(5)

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .build()

    /** Bounded: when chat outruns the network we drop lines instead of queueing minutes of backlog. */
    private val executor = ThreadPoolExecutor(
        1, 2, 30L, TimeUnit.SECONDS, ArrayBlockingQueue(MAX_QUEUE),
        { r -> Thread(r, "FamilyAddons-Translator").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardPolicy(),
    )

    private val cache = object : LinkedHashMap<String, Result>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Result>) = size > CACHE_SIZE
    }

    private val windowCount = AtomicInteger(0)
    @Volatile private var windowStart = 0L
    @Volatile private var backoffUntil = 0L
    @Volatile private var backoffWarned = false

    /** Set when the backend is parked, so the UI can explain itself once. */
    @Volatile var lastError: String? = null
        private set

    /**
     * Translates [raw] into [targetLang], calling [onDone] on a worker thread —
     * callers that touch the game must hop back to the client thread
     * themselves. [onDone] receives null when the line was dropped or failed.
     *
     * @param deep shift-click mode: AI only, big model, high reasoning effort,
     *        and the slang gets explained in [Result.notes]. Slower; never used
     *        for auto-translate.
     */
    fun translate(
        raw: String,
        targetLang: String,
        extraProtected: Set<String>,
        deep: Boolean = false,
        onDone: (Result?) -> Unit,
    ) {
        val key = (if (deep) "deep|" else "") + "$targetLang|$raw"
        synchronized(cache) { cache[key] }?.let { onDone(it); return }

        if (System.currentTimeMillis() < backoffUntil) {
            onDone(null)
            return
        }

        executor.execute {
            try {
                val result = runBlockingTranslate(raw, targetLang, extraProtected, deep)
                if (result != null) synchronized(cache) { cache[key] = result }
                onDone(result)
            } catch (e: Exception) {
                FamilyAddons.LOGGER.warn("Translator: request failed: ${e.message}")
                onDone(null)
            }
        }
    }

    /** Blocking variant for callers that already run off-thread (commands). */
    fun runBlockingTranslate(raw: String, targetLang: String, extraProtected: Set<String>, deep: Boolean = false): Result? {
        if (!consumeRateLimitToken()) return null

        val cfg = FamilyConfigManager.config.translator
        val guess = SlangDictionary.guessLanguage(raw)
        val prepared = SlangDictionary.preprocess(
            raw = raw,
            sourceGuess = guess,
            extraProtected = extraProtected,
            protectTerms = cfg.protectGameTerms,
            expandSlang = cfg.expandSlang,
        )
        if (prepared.text.isBlank()) return null

        // AI first when selected; if the worker is unreachable (not deployed,
        // bad URL, 5xx) fall through to Google so the feature still works.
        // A 429 from the worker parks everything instead — see translateWithAi.
        // Deep mode is AI-only: Google cannot explain anything.
        val backendResult = when {
            deep -> translateWithAi(prepared.text, targetLang, cfg.aiEndpoint.trim(), deep = true)
            cfg.backend == 0 -> translateWithAi(prepared.text, targetLang, cfg.aiEndpoint.trim())
                ?: if (System.currentTimeMillis() < backoffUntil) null else translateWithGoogle(prepared.text, targetLang)
            else -> translateWithGoogle(prepared.text, targetLang)
        } ?: return null

        val restored = SlangDictionary.restore(backendResult.text, prepared, targetLang)
        if (restored.isBlank()) return null

        return Result(
            text = restored,
            detected = backendResult.detected.ifBlank { guess ?: "?" },
            uncertain = prepared.slangRatio >= 0.34,
            notes = backendResult.notes?.let { SlangDictionary.restore(it, prepared, targetLang) }?.takeIf { it.isNotBlank() },
        )
    }

    /** Sliding-ish window limiter — cheap, and enough to stay well under any sane quota. */
    private fun consumeRateLimitToken(): Boolean {
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (now - windowStart > WINDOW_MS) {
                windowStart = now
                windowCount.set(0)
            }
            if (windowCount.get() >= REQUESTS_PER_WINDOW) return false
            windowCount.incrementAndGet()
        }
        return true
    }

    private fun park(reason: String) {
        backoffUntil = System.currentTimeMillis() + BACKOFF_MS
        lastError = reason
        if (!backoffWarned) {
            backoffWarned = true
            FamilyAddons.LOGGER.warn("Translator paused for 5 minutes: $reason")
        }
    }

    // ── Google ───────────────────────────────────────────────────────

    /**
     * Google's public endpoints. No key, no setup — but they are undocumented,
     * they translate slang literally (the dictionary passes soften that), and
     * Google bot-blocks IPs it dislikes with a 429. Two endpoints are tried:
     * the Chrome-dictionary one first because it is far less trigger-happy
     * about blocking, then the classic gtx one.
     */
    private fun translateWithGoogle(text: String, targetLang: String): BackendResult? {
        val encoded = URLEncoder.encode(text, StandardCharsets.UTF_8)

        googleChrome(encoded, targetLang)?.let { return it }
        return googleGtx(encoded, targetLang)
    }

    private val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    private fun googleGet(url: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder()
            .uri(URI(url))
            .timeout(Duration.ofSeconds(6))
            .header("User-Agent", BROWSER_UA)
            .GET()
            .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    /**
     * clients5.google.com/translate_a/t — the endpoint Chrome's built-in
     * dictionary uses. Response: [["translated", "es"]] (one entry per query).
     * Returns null on a 429 so the caller can try the next endpoint; a park
     * only happens once both have refused.
     */
    private fun googleChrome(encoded: String, targetLang: String): BackendResult? {
        val response = googleGet(
            "https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=auto&tl=$targetLang&q=$encoded"
        )
        if (response.statusCode() != 200) {
            FamilyAddons.LOGGER.debug("Translator: clients5 HTTP ${response.statusCode()}")
            return null
        }
        val root = runCatching { JsonParser.parseString(response.body()) as? JsonArray }.getOrNull() ?: return null
        val first = root.firstOrNull() ?: return null
        // With sl=auto each entry is ["text","lang"]; with a fixed source it is just "text".
        val translated: String
        val detected: String
        if (first.isJsonArray) {
            val entry = first.asJsonArray
            translated = entry.get(0)?.takeIf { !it.isJsonNull }?.asString?.trim() ?: return null
            detected = entry.takeIf { it.size() > 1 }?.get(1)?.takeIf { !it.isJsonNull }?.asString ?: ""
        } else {
            translated = first.asString.trim()
            detected = ""
        }
        return translated.takeIf { it.isNotEmpty() }?.let { BackendResult(it, detected) }
    }

    /**
     * translate.googleapis.com/translate_a/single (gtx). Response shape:
     * [[["translated","original",...],...], null, "es", ...]
     */
    private fun googleGtx(encoded: String, targetLang: String): BackendResult? {
        val response = googleGet(
            "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$targetLang&dt=t&q=$encoded"
        )
        when (response.statusCode()) {
            200 -> {}
            429 -> { park("Google is blocking translation requests from this IP (HTTP 429)"); return null }
            else -> { FamilyAddons.LOGGER.warn("Translator: Google HTTP ${response.statusCode()}"); return null }
        }

        val root = runCatching { JsonParser.parseString(response.body()) as? JsonArray }.getOrNull() ?: return null
        val segments = root.get(0) as? JsonArray ?: return null
        val builder = StringBuilder()
        for (segment in segments) {
            val chunk = (segment as? JsonArray)?.get(0)?.takeIf { !it.isJsonNull }?.asString ?: continue
            builder.append(chunk)
        }
        val translated = builder.toString().trim()
        if (translated.isEmpty()) return null

        val detected = runCatching { root.get(2).asString }.getOrDefault("")
        return BackendResult(translated, detected)
    }

    // ── AI worker ────────────────────────────────────────────────────

    /**
     * Posts to a worker the player runs themselves (see worker/translate-worker.js).
     * The prompt there knows it is reading Hypixel Skyblock chat, which is the
     * only thing that reliably handles slang the dictionary has not seen.
     *
     *   POST { "text": "...", "target": "en" }
     *   200  { "translation": "...", "detected": "es" }
     */
    /** Logged once per session so a missing worker does not spam the log every line. */
    @Volatile private var aiUnavailableLogged = false

    private fun translateWithAi(text: String, targetLang: String, endpoint: String, deep: Boolean = false): BackendResult? {
        if (endpoint.isBlank()) return null

        val payload = com.google.gson.JsonObject().apply {
            addProperty("text", text)
            addProperty("target", targetLang)
            addProperty("source", "auto")
            addProperty("context", "Hypixel SkyBlock in-game chat")
            if (deep) addProperty("mode", "deep")
        }

        val response = try {
            val request = HttpRequest.newBuilder()
                .uri(URI(endpoint))
                .timeout(Duration.ofSeconds(if (deep) 30 else 10))
                .header("Content-Type", "application/json")
                .header("User-Agent", "FamilyAddons/${FamilyAddons.VERSION}")
                .header(KeyFetcher.SECRET_HEADER, KeyFetcher.SECRET_TOKEN)
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                .build()
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            logAiUnavailable("unreachable: ${e.message}")
            return null
        }

        when (response.statusCode()) {
            200 -> {}
            429 -> { park("AI endpoint rate limited (HTTP 429)"); return null }
            else -> { logAiUnavailable("HTTP ${response.statusCode()}"); return null }
        }

        val json = runCatching { JsonParser.parseString(response.body()).asJsonObject }.getOrNull() ?: return null
        val translated = json.get("translation")?.takeIf { !it.isJsonNull }?.asString?.trim() ?: return null
        if (translated.isEmpty()) return null
        val detected = json.get("detected")?.takeIf { !it.isJsonNull }?.asString ?: ""
        val notes = json.get("notes")?.takeIf { !it.isJsonNull }?.asString?.trim()?.takeIf { it.isNotEmpty() }
        return BackendResult(translated, detected, notes)
    }

    private fun logAiUnavailable(reason: String) {
        if (aiUnavailableLogged) return
        aiUnavailableLogged = true
        FamilyAddons.LOGGER.warn("Translator: AI worker $reason — falling back to Google for this session")
    }

    /** Clears the backoff after the player changes backend or endpoint. */
    fun resetBackoff() {
        backoffUntil = 0
        backoffWarned = false
        lastError = null
    }

    fun clearCache() {
        synchronized(cache) { cache.clear() }
    }
}
