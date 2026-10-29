package com.openminis.app.speech

import java.util.Locale

/**
 * [T-431] Locale helpers shared by the chat voice input
 * ([SystemSpeechRecognitionEngine]) and the `android-speech` CLI, so both send
 * the recognizer the same tag and offer the same fallback list.
 */
object RecognizerLocales {
    /**
     * Curated list the chat voice input offers when the system refuses to
     * enumerate (the device default goes first, see [SystemSpeechRecognitionEngine]).
     * It covers the major languages Google and most OEM recognizers support; it
     * is NOT a report of what this device's recognizer can do.
     */
    val BUILTIN_TAGS: List<String> = listOf(
        "en-US", "zh-CN", "zh-TW", "ja-JP", "ko-KR", "es-ES", "fr-FR", "de-DE", "it-IT",
        "pt-BR", "ru-RU", "ar-SA", "hi-IN", "id-ID", "th-TH", "vi-VN", "tr-TR",
    )

    /**
     * [T-android-asr-script-subtag] Strip the SCRIPT subtag from a language tag
     * before handing it to the recognizer.
     *
     * `Locale.getDefault()` on a Simplified-Chinese device is `zh-Hans-CN`, and
     * `toLanguageTag()` faithfully returns "zh-Hans-CN". Google's recognizer
     * matches its language packs on plain `language-REGION` tags, so the script
     * subtag makes it report "Locale not supported" / "Failed to get language
     * pack of required locale" and fail the session with ERROR_LANGUAGE_
     * NOT_SUPPORTED (12) — the mic opens and immediately closes. Observed on a
     * Pixel 4a whose system locale is zh-Hans-CN.
     *
     * Reducing to language(+region) keeps the meaningful part ("zh-CN") and is
     * safe for every other locale: tags without a script subtag are unchanged.
     */
    fun recognizerTag(locale: Locale): String {
        val lang = locale.language
        var region = locale.country
        // A script-only locale carries no region ("zh-Hans"), and a bare "zh"
        // is just as unresolvable to the recognizer as "zh-Hans" — it needs a
        // concrete language pack. Derive the region from the script, which is
        // exactly the information the script subtag encodes.
        if (region.isNullOrEmpty() && lang == "zh") {
            region = if (locale.script == "Hant") "TW" else "CN"
        }
        // Last resort for any other script-only/region-less locale: ask ICU to
        // fill in the likely region (zh-Hans -> zh-Hans-CN, etc.).
        if (region.isNullOrEmpty()) {
            region = runCatching { Locale.Builder().setLocale(locale).build() }
                .getOrNull()?.country.orEmpty()
        }
        return if (region.isEmpty()) lang else "$lang-$region"
    }

    /** [recognizerTag] for a user-typed tag ("zh_Hans_CN", "zh-Hans-CN", "en"). */
    fun recognizerTag(tag: String): String =
        recognizerTag(Locale.forLanguageTag(tag.trim().replace('_', '-')))
}
