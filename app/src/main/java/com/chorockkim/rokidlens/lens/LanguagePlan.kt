package com.chorockkim.rokidlens.lens

/**
 * Which language pair to translate with. Pure, so it is unit-tested without ML Kit.
 * Language codes are ML Kit's (BCP-47 base codes such as "en", "ja", "zh", "ko").
 */
object LanguagePlan {
    const val KOREAN = "ko"
    const val ENGLISH = "en"

    /** Source language: what language-id detected, else the script the text was read with. */
    internal fun source(detected: String?, script: OcrScript, supported: (String) -> Boolean): String {
        val tag = detected?.takeUnless { it == "und" || it.isBlank() }?.substringBefore('-')?.lowercase()
        if (tag != null && supported(tag)) return tag
        return when (script) {
            OcrScript.LATIN -> ENGLISH
            OcrScript.JAPANESE -> "ja"
            OcrScript.CHINESE -> "zh"
            OcrScript.KOREAN -> KOREAN
            OcrScript.DEVANAGARI -> "hi"
        }
    }

    /** Everything goes to [preferred] (Korean); Korean text itself goes to English. */
    fun target(source: String, preferred: String = KOREAN): String =
        if (source == preferred) (if (preferred == ENGLISH) KOREAN else ENGLISH) else preferred

    /** Whether a recognized line is worth translating (not a stray symbol or a lone digit). */
    fun worthTranslating(text: String): Boolean {
        val letters = text.count { it.isLetter() }
        return letters >= 2
    }
}
