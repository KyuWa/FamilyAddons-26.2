package org.kyowa.familyaddons.features.translator

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.client.Minecraft
import org.kyowa.familyaddons.FamilyAddons
import java.io.File

/**
 * The two dictionary passes that sit around the raw translation.
 *
 * Machine translators expect prose, and Skyblock chat is not prose — it is
 * shorthand ("xfa", "tmb", "wanna"), game jargon that must survive untouched
 * ("carry t5", "Kuudra", "f7"), and slang whose literal translation is wrong
 * ("manco" is not "one-armed"). So:
 *
 *   preprocess  masks game terms behind TRM<n> placeholders the translator
 *               leaves alone, then expands shorthand into full words
 *   restore     puts the masked terms back and rewrites the handful of
 *               literal-translation artifacts that still get through
 *
 * Defaults ship in translator_slang.json; a copy is written to
 * config/familyaddons/translator_slang.json on first run so players can extend
 * it without rebuilding. [reload] re-reads that copy.
 */
object SlangDictionary {

    private const val RESOURCE = "/translator_slang.json"
    private const val USER_FILE = "config/familyaddons/translator_slang.json"

    private var protectedTerms: Set<String> = emptySet()
    private var protectedPatterns: List<Regex> = emptyList()
    /** language code -> (shorthand -> full words); "common" applies to every language. */
    private var expansions: Map<String, Map<String, String>> = emptyMap()
    /** target language code -> (literal artifact -> natural phrasing). */
    private var postFixes: Map<String, Map<String, String>> = emptyMap()

    @Volatile private var loaded = false

    /** Result of [preprocess], carried through to [restore]. */
    data class Prepared(
        /** Text to hand the translator. */
        val text: String,
        /** placeholder -> original game term. */
        val masked: Map<String, String>,
        /**
         * Share of word tokens that were short and unrecognised — high values
         * mean the line was mostly slang the dictionaries did not know, so the
         * translation is a guess.
         */
        val slangRatio: Double,
    )

    // ── Loading ──────────────────────────────────────────────────────

    fun ensureLoaded() {
        if (!loaded) reload()
    }

    /**
     * The bundled dictionary is the base; the player's file is merged on top
     * so their additions win and shipped updates still reach them.
     */
    fun reload() {
        loaded = true
        val merged = Dict()
        val bundled = readBundled()?.let { parseSafely(it, "bundled") }
        val user = readUserFile()?.let { parseSafely(it, "user") }
        if (bundled == null && user == null) {
            FamilyAddons.LOGGER.warn("Translator: no slang dictionary found, running without one")
            return
        }
        bundled?.let { merged.mergeFrom(it) }
        user?.let { merged.mergeFrom(it) }

        protectedTerms = merged.protectedTerms
        protectedPatterns = merged.protectedPatterns
        expansions = merged.expansions
        postFixes = merged.postFixes
        FamilyAddons.LOGGER.info(
            "Translator: dictionary loaded (${protectedTerms.size} protected terms, " +
                "${expansions.values.sumOf { it.size }} expansions" +
                (user?.let { ", ${it.expansions.values.sumOf { s -> s.size }} from your file" } ?: "") + ")"
        )
    }

    private class Dict {
        val protectedTerms = LinkedHashSet<String>()
        val protectedPatterns = ArrayList<Regex>()
        val expansions = LinkedHashMap<String, LinkedHashMap<String, String>>()
        val postFixes = LinkedHashMap<String, LinkedHashMap<String, String>>()

        fun mergeFrom(other: Dict) {
            protectedTerms += other.protectedTerms
            protectedPatterns += other.protectedPatterns
            other.expansions.forEach { (lang, map) -> expansions.getOrPut(lang) { LinkedHashMap() }.putAll(map) }
            other.postFixes.forEach { (lang, map) -> postFixes.getOrPut(lang) { LinkedHashMap() }.putAll(map) }
        }
    }

    private fun parseSafely(root: JsonObject, label: String): Dict? = try {
        parse(root)
    } catch (e: Exception) {
        FamilyAddons.LOGGER.warn("Translator: $label dictionary parse failed: ${e.message}")
        null
    }

    private fun parse(root: JsonObject): Dict {
        val dict = Dict()
        root.getAsJsonArray("protectedTerms")
            ?.mapNotNull { it.asString?.lowercase()?.takeIf { s -> s.isNotBlank() } }
            ?.let { dict.protectedTerms += it }

        root.getAsJsonArray("protectedPatterns")
            ?.mapNotNull {
                runCatching { Regex(it.asString, RegexOption.IGNORE_CASE) }
                    .onFailure { e -> FamilyAddons.LOGGER.warn("Translator: bad protected pattern: ${e.message}") }
                    .getOrNull()
            }?.let { dict.protectedPatterns += it }

        root.getAsJsonObject("expansions")?.entrySet()?.forEach { (lang, section) ->
            val map = dict.expansions.getOrPut(lang) { LinkedHashMap() }
            section.asJsonObject.entrySet().forEach { (k, v) -> map[k.lowercase()] = v.asString }
        }
        root.getAsJsonObject("postFixes")?.entrySet()?.forEach { (lang, section) ->
            val map = dict.postFixes.getOrPut(lang) { LinkedHashMap() }
            section.asJsonObject.entrySet().forEach { (k, v) -> map[k.lowercase()] = v.asString }
        }
        return dict
    }

    private fun readBundled(): JsonObject? = try {
        SlangDictionary::class.java.getResourceAsStream(RESOURCE)?.use {
            JsonParser.parseReader(it.reader()).asJsonObject
        }
    } catch (e: Exception) {
        FamilyAddons.LOGGER.warn("Translator: bundled dictionary unreadable: ${e.message}")
        null
    }

    /** Starter file written the first time, so players see the shape without digging through the jar. */
    private val USER_TEMPLATE = """
        {
          "_comment": [
            "FamilyAddons translator - your additions. Merged on top of the built-in dictionary; yours win.",
            "protectedTerms: words the translator must leave alone, e.g. [\"jodi\", \"webito\"]",
            "expansions: chat shorthand -> full words, per source language: {\"es\": {\"ctm\": \"chinga tu madre\"}}",
            "postFixes: literal translation -> natural phrasing, per target language: {\"en\": {\"take me\": \"carry me\"}}",
            "Run /fa tr reload after editing."
          ],
          "protectedTerms": [],
          "protectedPatterns": [],
          "expansions": { "es": {}, "en": {}, "pt": {}, "common": {} },
          "postFixes": { "en": {}, "es": {} }
        }
    """.trimIndent()

    /** Reads the player's additions, creating an empty template the first time. */
    private fun readUserFile(): JsonObject? {
        val file = File(Minecraft.getInstance().gameDirectory, USER_FILE)
        try {
            if (!file.exists()) {
                file.parentFile.mkdirs()
                file.writeText(USER_TEMPLATE)
                return null
            }
            return JsonParser.parseReader(file.reader()).asJsonObject
        } catch (e: Exception) {
            FamilyAddons.LOGGER.warn("Translator: your dictionary file is unreadable, using built-in only: ${e.message}")
            return null
        }
    }

    // ── Language guessing ────────────────────────────────────────────

    private val ES_WORDS = setOf(
        "que", "de", "la", "el", "en", "un", "una", "por", "con", "para", "pero", "muy", "esta", "si",
        "yo", "tu", "me", "te", "se", "lo", "los", "las", "del", "al", "es", "son", "tiene", "hay",
        "mas", "como", "cuando", "donde", "quien", "porque", "tambien", "gracias", "hola", "buenas",
        "alguien", "puede", "puedo", "quiero", "vamos", "ahora", "nada", "todo", "bien", "mal", "sin",
        "sobre", "hasta", "desde", "eres", "estoy", "soy", "voy", "tengo", "hace", "dame", "mira",
        // Shorthand that only Spanish speakers write — strong signals on short lines.
        "q", "xq", "pq", "xfa", "tmb", "tb", "ns", "toy", "wey", "manco", "jaja", "jajaja", "ke",
        "ctm", "ptm", "nmms", "alv", "pndjo", "kbron", "jsjs",
        "haces", "hago", "sabes", "puedes", "quieres", "vienes", "ven", "vente", "oye", "che", "wena",
        "carrea", "carreas", "carrear", "carreame", "llevame", "alguno", "algun", "ayuda", "ayudame",
    )
    private val PT_WORDS = setOf(
        "voce", "nao", "sim", "obrigado", "valeu", "beleza", "mano", "cara", "tem", "muito", "quando",
        "onde", "quem", "tambem", "alguem", "pode", "quero", "vamos", "agora", "tudo", "bem", "sem",
        "ate", "depois", "mesmo", "hoje", "fazer", "estou", "esta", "isso", "aqui", "gente",
    )
    private val EN_WORDS = setOf(
        "the", "you", "is", "are", "and", "to", "of", "i", "a", "do", "can", "get", "have", "want",
        "need", "please", "thanks", "someone", "anyone", "why", "what", "when", "where", "who",
        "because", "also", "now", "good", "bad", "with", "about", "from", "my", "your", "me", "it",
        "that", "this", "for", "in", "on", "we", "not", "yes", "no", "how", "any", "still",
        // English-only chat shorthand.
        "u", "ur", "wanna", "gonna", "pls", "plz", "idk", "bro", "dude", "guys", "anyone", "someone",
        "haha", "lmao", "nty", "nvm", "tbh", "ngl", "imo", "thx", "ty",
    )

    /**
     * Cheap guess used only to pick which expansion section to apply — the real
     * detection comes back from the translator. Returns null when the line is
     * too short or too ambiguous to call, in which case only "common"
     * expansions run.
     */
    fun guessLanguage(text: String): String? {
        // Script is a giveaway before any word counting: mostly Arabic
        // letters means Arabic, mostly Cyrillic means Russian (close enough
        // to pick a dictionary section; the backend still detects properly).
        val letters = text.filter { it.isLetter() }
        if (letters.isNotEmpty()) {
            val arabic = letters.count { it.code in 0x0600..0x06FF }
            val cyrillic = letters.count { it.code in 0x0400..0x04FF }
            if (arabic * 2 > letters.length) return "ar"
            if (cyrillic * 2 > letters.length) return "ru"
        }

        val lower = text.lowercase()
        val words = lower.split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null

        var es = 0
        var pt = 0
        var en = 0
        val stripped = words.map { stripAccents(it) }
        for (w in stripped) {
            if (w in ES_WORDS) es++
            if (w in PT_WORDS) pt++
            if (w in EN_WORDS) en++
        }
        // Characters that only really appear in one of the two Iberian languages.
        if (lower.any { it == 'ñ' } || lower.any { it == '¿' || it == '¡' }) es += 2
        if (lower.any { it == 'ã' || it == 'õ' } || lower.contains("ção")) pt += 2

        val best = maxOf(es, pt, en)
        if (best == 0) return null
        // Require a clear winner; a tie means we cannot tell.
        return when {
            es == best && es > pt && es > en -> "es"
            pt == best && pt > es && pt > en -> "pt"
            en == best && en > es && en > pt -> "en"
            else -> null
        }
    }

    private fun stripAccents(s: String): String = java.text.Normalizer
        .normalize(s, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")

    // ── Pre-pass ─────────────────────────────────────────────────────

    private val TOKEN_SPLIT = Regex("(\\s+)")

    /**
     * @param extraProtected words the caller wants left alone as well — player
     *        names from the tab list and the config's own list.
     */
    fun preprocess(
        raw: String,
        sourceGuess: String?,
        extraProtected: Set<String>,
        protectTerms: Boolean,
        expandSlang: Boolean,
    ): Prepared {
        ensureLoaded()

        var text = normalize(raw)
        val masked = LinkedHashMap<String, String>()

        if (protectTerms) {
            // Patterns first: they cover multi-character forms like "t5" and
            // "10m" that the per-token pass below would not match as words.
            for (pattern in protectedPatterns) {
                text = pattern.replace(text) { m ->
                    val key = "TRM${masked.size}"
                    masked[key] = m.value
                    key
                }
            }
        }

        // Multi-word entries ("ضف وجهك", "no mames") can't be matched one
        // token at a time, so they get a whole-phrase pass first.
        if (expandSlang) text = expandPhrases(text, sourceGuess)

        var wordTokens = 0
        var unknown = 0
        val out = StringBuilder()

        for (part in text.split(TOKEN_SPLIT)) {
            if (part.isBlank()) {
                out.append(part)
                continue
            }
            // Keep surrounding punctuation outside the lookup so "hola," and
            // "hola" hit the same dictionary entry.
            val lead = part.takeWhile { !it.isLetterOrDigit() }
            if (lead.length == part.length) {
                // Pure punctuation ("=", "?", ":)") — nothing to look up. Has
                // to be checked before taking the trail or the substring
                // bounds cross and throw.
                out.append(part).append(' ')
                continue
            }
            val trail = part.takeLastWhile { !it.isLetterOrDigit() }
            val core = part.substring(lead.length, part.length - trail.length)
            val lower = core.lowercase()
            wordTokens++

            // Already a placeholder from the pattern pass.
            if (core.startsWith("TRM") && core.drop(3).all { it.isDigit() }) {
                out.append(part).append(' ')
                continue
            }

            if (protectTerms && (lower in protectedTerms || lower in extraProtected)) {
                val key = "TRM${masked.size}"
                masked[key] = core
                out.append(lead).append(key).append(trail).append(' ')
                continue
            }

            val replacement = if (expandSlang) lookupExpansion(lower, sourceGuess) else null
            if (replacement != null) {
                out.append(lead).append(replacement).append(trail).append(' ')
                continue
            }

            // Short, unrecognised, not a real word in any of our lists: most
            // likely slang the dictionary has not learned yet.
            if (core.length <= 3 && stripAccents(lower) !in EN_WORDS &&
                stripAccents(lower) !in ES_WORDS && stripAccents(lower) !in PT_WORDS &&
                !core.all { it.isDigit() }
            ) unknown++

            out.append(part).append(' ')
        }

        // The "short unknown word" heuristic only means something for Latin
        // script; two-letter Arabic or Cyrillic words are ordinary words, not
        // slang, so lines written mostly in another script are never flagged.
        val letters = raw.filter { it.isLetter() }
        val latinShare = if (letters.isEmpty()) 1.0 else letters.count { it.code < 0x0250 }.toDouble() / letters.length
        val ratio = if (wordTokens == 0 || latinShare < 0.5) 0.0 else unknown.toDouble() / wordTokens
        return Prepared(out.toString().trim(), masked, ratio)
    }

    private fun lookupExpansion(token: String, sourceGuess: String?): String? {
        expansions["common"]?.get(token)?.let { return it }
        val section = sourceGuess ?: return null
        return expansions[section]?.get(token)
    }

    /** Applies the dictionary entries whose key contains a space, longest first so "no mames wey" beats "no mames". */
    private fun expandPhrases(text: String, sourceGuess: String?): String {
        val sections = listOfNotNull(expansions["common"], sourceGuess?.let { expansions[it] })
        val phrases = sections.flatMap { it.entries }.filter { it.key.any { c -> c.isWhitespace() } }
        if (phrases.isEmpty()) return text
        var result = text
        for ((phrase, replacement) in phrases.sortedByDescending { it.key.length }) {
            // \b is unreliable for Arabic, so bound by "not a letter" on both sides.
            val pattern = Regex("(?i)(?<!\\p{L})" + Regex.escape(phrase) + "(?!\\p{L})")
            result = pattern.replace(result, Regex.escapeReplacement(replacement))
        }
        return result
    }

    /**
     * Collapses the stretched spellings ("holaaaaa", "!!!!") that make
     * translators fall back to transliteration.
     */
    private fun normalize(text: String): String = text
        .replace(Regex("(\\p{L})\\1{2,}"), "$1")
        .replace(Regex("([!?.,])\\1{1,}"), "$1")
        .replace(Regex("\\s{2,}"), " ")
        .trim()

    // ── Post-pass ────────────────────────────────────────────────────

    fun restore(translated: String, prepared: Prepared, targetLang: String): String {
        var text = translated

        // Translators lowercase placeholders and sometimes split "TRM0" into
        // "TRM 0", so match loosely rather than by exact string.
        if (prepared.masked.isNotEmpty()) {
            text = Regex("(?i)\\bTRM\\s*(\\d+)\\b").replace(text) { m ->
                prepared.masked["TRM${m.groupValues[1]}"] ?: m.value
            }
        }

        postFixes[targetLang]?.forEach { (artifact, natural) ->
            text = Regex("(?i)" + Regex.escape(artifact)).replace(text, natural)
        }

        return text.replace(Regex("\\s{2,}"), " ").trim()
    }
}
