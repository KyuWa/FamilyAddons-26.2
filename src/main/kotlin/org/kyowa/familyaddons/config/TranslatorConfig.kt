package org.kyowa.familyaddons.config

import com.google.gson.annotations.Expose
import io.github.notenoughupdates.moulconfig.annotations.ConfigAccordionId
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorAccordion
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorText
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption

/**
 * Chat translator. The source language is auto-detected, so Spanish to English
 * works out of the box and every other language comes along for free.
 *
 * The GUI shows only the handful of switches a player actually needs. The
 * tuning knobs (slang passes, backend, display details) keep sensible
 * defaults and live in the JSON only — see the "advanced" block at the bottom.
 *
 * Languages are typed as text ("Spanish", "es", "español") and resolved by
 * [codeOf]; see the companion for the accepted spellings.
 */
class TranslatorConfig {

    @Expose @JvmField
    @ConfigOption(name = "Enable Translator", desc = "Master switch for everything below.")
    @ConfigEditorBoolean
    var enabled = false

    @Expose @JvmField
    @ConfigOption(name = "Click To Translate", desc = "Click any player's message in chat to see it in your language.")
    @ConfigEditorBoolean
    var clickToTranslate = true

    @Expose @JvmField
    @ConfigOption(name = "Auto-Translate", desc = "Translate messages as they arrive, no clicking needed. Only the chats ticked under §eAuto-Translate Channels§7.")
    @ConfigEditorBoolean
    var autoTranslate = false

    @Expose @JvmField
    @ConfigOption(name = "Translate Into", desc = "The language you read — just type it: §eEnglish§7, §eSpanish§7, §eArabic§7… Messages already in it are left alone.")
    @ConfigEditorText
    var targetLanguage = "English"

    @Expose @JvmField
    @ConfigOption(name = "Send Language", desc = "What your own text is turned into by §e/tr <text>§7 (shows it, click to copy), §e/ptr§7 (sends to party) and §e/gtr§7 (sends to guild). Type a language name.")
    @ConfigEditorText
    var outgoingLanguage = "Spanish"

    // ── Auto-translate channels ──────────────────────────────────────
    @Expose @JvmField
    @ConfigOption(name = "Auto-Translate Channels", desc = "Which chats §eAuto-Translate§7 listens to. Clicking works everywhere regardless.")
    @ConfigEditorAccordion(id = 0)
    var channelsAccordion = false

    @Expose @JvmField
    @ConfigAccordionId(id = 0)
    @ConfigOption(name = "Party", desc = "§9Party >§7 messages.")
    @ConfigEditorBoolean
    var channelParty = true

    @Expose @JvmField
    @ConfigAccordionId(id = 0)
    @ConfigOption(name = "Guild", desc = "§2Guild >§7 and §2Officer >§7 messages.")
    @ConfigEditorBoolean
    var channelGuild = true

    @Expose @JvmField
    @ConfigAccordionId(id = 0)
    @ConfigOption(name = "Co-op", desc = "§bCo-op >§7 messages.")
    @ConfigEditorBoolean
    var channelCoop = true

    @Expose @JvmField
    @ConfigAccordionId(id = 0)
    @ConfigOption(name = "Direct Messages", desc = "§dFrom§7 / §dTo§7 whispers.")
    @ConfigEditorBoolean
    var channelDm = true

    @Expose @JvmField
    @ConfigAccordionId(id = 0)
    @ConfigOption(name = "Public Chat", desc = "Everyone in the lobby. Off by default — it's busy, and every line is a request.")
    @ConfigEditorBoolean
    var channelPublic = false

    // ── Advanced (JSON only: config/familyaddons/config.json → "translator") ──
    // Deliberately not in the GUI. Defaults are right for nearly everyone;
    // they're here so a curious player can still tweak them without a rebuild.

    /** Skip posting a translation when the message was already in the target language. */
    @Expose @JvmField var skipSameLanguage = true
    /** Comma-separated source language codes to auto-translate; empty = all. */
    @Expose @JvmField var sourceFilter = ""
    /** Also auto-translate messages you sent yourself. */
    @Expose @JvmField var translateOwn = false
    /** Rewrite chat shorthand ("q", "xfa", "wanna") into full words before translating. */
    @Expose @JvmField var expandSlang = true
    /** Mask Skyblock terms so they come back untranslated ("carry" stays "carry"). */
    @Expose @JvmField var protectGameTerms = true
    /** Extra comma-separated words to never translate. */
    @Expose @JvmField var extraProtectedTerms = ""
    /** Tag lines that were mostly unrecognised slang with "(?)". */
    @Expose @JvmField var markUncertain = true
    /** 0 = AI worker (falls back to Google if unreachable), 1 = Google only. */
    @Expose @JvmField var backend = 0
    /** Translation worker URL used by the AI backend. */
    @Expose @JvmField var aiEndpoint = "https://fa-translate.220395610.workers.dev"
    /** Include the sender's name on the translated line. */
    @Expose @JvmField var showSender = true
    /** Show the detected language, e.g. [es → en]. */
    @Expose @JvmField var showLanguageTag = true
    /** Hovering the translated line shows the original. */
    @Expose @JvmField var hoverOriginal = true
    /** Messages longer than this are not translated. */
    @Expose @JvmField var maxLength = 200f

    companion object {
        /** Google language codes; index-matched to [LANGUAGE_NAMES]. Order is also the legacy dropdown order. */
        @JvmField
        val LANGUAGE_CODES = arrayOf(
            "en", "es", "pt", "fr", "de", "it", "nl", "pl", "ru", "tr",
            "sv", "no", "da", "fi", "cs", "ro", "hu", "el", "uk", "ar",
            "iw", "hi", "id", "vi", "th", "zh-CN", "zh-TW", "ja", "ko", "tl", "ms",
        )

        /** Human-readable names, index-matched to [LANGUAGE_CODES]. */
        @JvmField
        val LANGUAGE_NAMES = arrayOf(
            "English", "Spanish", "Portuguese", "French", "German", "Italian", "Dutch", "Polish", "Russian", "Turkish",
            "Swedish", "Norwegian", "Danish", "Finnish", "Czech", "Romanian", "Hungarian", "Greek", "Ukrainian", "Arabic",
            "Hebrew", "Hindi", "Indonesian", "Vietnamese", "Thai", "Chinese (Simplified)", "Chinese (Traditional)", "Japanese", "Korean", "Filipino", "Malay",
        )

        /** Other spellings players type, mapped to a code. Keys are lowercase. */
        private val ALIASES = mapOf(
            "ingles" to "en", "inglés" to "en", "inglese" to "en",
            "espanol" to "es", "español" to "es", "castellano" to "es", "spain" to "es", "mexican" to "es",
            "portugues" to "pt", "português" to "pt", "brazilian" to "pt", "brasil" to "pt", "brazil" to "pt", "br" to "pt",
            "francais" to "fr", "français" to "fr", "france" to "fr",
            "deutsch" to "de", "aleman" to "de", "alemán" to "de", "germany" to "de",
            "italiano" to "it", "italy" to "it",
            "nederlands" to "nl", "holland" to "nl",
            "polski" to "pl", "poland" to "pl",
            "russia" to "ru", "русский" to "ru", "ruso" to "ru",
            "turkce" to "tr", "türkçe" to "tr", "turkey" to "tr", "turco" to "tr",
            "arab" to "ar", "arabe" to "ar", "árabe" to "ar", "عربي" to "ar", "العربية" to "ar",
            "chinese" to "zh-CN", "mandarin" to "zh-CN", "zh" to "zh-CN", "china" to "zh-CN", "chino" to "zh-CN",
            "traditional chinese" to "zh-TW", "taiwan" to "zh-TW",
            "japan" to "ja", "jp" to "ja", "japones" to "ja", "japonés" to "ja",
            "korea" to "ko", "kr" to "ko", "coreano" to "ko",
            "he" to "iw", "israel" to "iw",
            "tagalog" to "tl", "philippines" to "tl",
            "bahasa" to "id", "indonesia" to "id",
            "vietnam" to "vi", "greece" to "el", "ukraine" to "uk", "hungary" to "hu", "romania" to "ro",
            "czech republic" to "cs", "sweden" to "sv", "norway" to "no", "denmark" to "da", "finland" to "fi",
            "india" to "hi", "thailand" to "th", "malaysia" to "ms",
        )

        /**
         * Turns whatever the player typed into a language code. Accepts names
         * ("Spanish"), codes ("es"), aliases ("español"), and — for configs
         * written before the text box — the old dropdown index ("1"). Unknown
         * text is passed through lowercased so the AI backend can still act on
         * a language we never listed.
         */
        fun codeOf(input: String): String {
            val s = input.trim()
            if (s.isEmpty()) return "en"
            if (s.all { it.isDigit() }) return LANGUAGE_CODES.getOrElse(s.toInt()) { "en" }
            val lower = s.lowercase()
            LANGUAGE_CODES.indexOfFirst { it.equals(lower, ignoreCase = true) }.takeIf { it >= 0 }?.let { return LANGUAGE_CODES[it] }
            LANGUAGE_NAMES.indexOfFirst { it.equals(s, ignoreCase = true) }.takeIf { it >= 0 }?.let { return LANGUAGE_CODES[it] }
            ALIASES[lower]?.let { return it }
            // "Chinese (Simplified)" typed without the bracket, etc.
            LANGUAGE_NAMES.indexOfFirst { it.substringBefore(" (").equals(s, ignoreCase = true) }.takeIf { it >= 0 }?.let { return LANGUAGE_CODES[it] }
            return lower
        }

        /** Display name for whatever the player typed, e.g. "es" → "Spanish". Unknown input is shown as typed. */
        fun nameOf(input: String): String {
            val code = codeOf(input)
            val i = LANGUAGE_CODES.indexOf(code)
            return if (i >= 0) LANGUAGE_NAMES[i] else input.trim().ifEmpty { "English" }
        }

        /** Legacy dropdown index → name, for migrating old configs. */
        fun nameOfIndex(index: Int): String = LANGUAGE_NAMES.getOrElse(index) { "English" }
    }
}
