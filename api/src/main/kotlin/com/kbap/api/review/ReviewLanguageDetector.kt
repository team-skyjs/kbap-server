package com.kbap.api.review

import com.github.pemistahl.lingua.api.Language
import com.github.pemistahl.lingua.api.LanguageDetector
import com.github.pemistahl.lingua.api.LanguageDetectorBuilder
import com.kbap.common.domain.LanguageCode
import org.springframework.stereotype.Component
import java.lang.Character.UnicodeScript
import java.nio.charset.Charset

@Component
class ReviewLanguageDetector {
    private val latinDetector: LanguageDetector = LanguageDetectorBuilder
        .fromLanguages(*(LATIN_APP_LANGUAGES.keys + OTHER_LATIN_LANGUAGES).toTypedArray())
        .withLowAccuracyMode()
        .withPreloadedLanguageModels()
        .build()

    fun detect(content: String?): LanguageCode? {
        val prose = content?.replace(NOT_PROSE, " ") ?: return null
        val letters = prose.codePoints().toArray().filter(::isCountedLetter)
        if (letters.isEmpty()) return null
        val countByScript = letters.groupingBy { UnicodeScript.of(it) }.eachCount()

        fun shareOf(vararg scripts: UnicodeScript): Double = scripts.sumOf { countByScript[it] ?: 0 }.toDouble() / letters.size
        fun has(vararg scripts: UnicodeScript): Boolean = scripts.any { it in countByScript }

        return when {
            has(UnicodeScript.HIRAGANA, UnicodeScript.KATAKANA) &&
                shareOf(UnicodeScript.HIRAGANA, UnicodeScript.KATAKANA, UnicodeScript.HAN) >= DOMINANT_SHARE -> LanguageCode.JA
            shareOf(UnicodeScript.HANGUL) >= DOMINANT_SHARE -> LanguageCode.KO
            shareOf(UnicodeScript.THAI) >= DOMINANT_SHARE -> LanguageCode.TH
            shareOf(UnicodeScript.CYRILLIC) >= DOMINANT_SHARE -> russianOf(letters)
            shareOf(UnicodeScript.HAN) >= DOMINANT_SHARE -> chineseOf(letters)
            shareOf(UnicodeScript.LATIN) >= DOMINANT_SHARE -> latinOf(prose)
            else -> null
        }
    }

    private fun isCountedLetter(codePoint: Int): Boolean {
        if (!Character.isLetter(codePoint)) return false
        return when (UnicodeScript.of(codePoint)) {
            UnicodeScript.COMMON, UnicodeScript.INHERITED -> false
            UnicodeScript.HANGUL -> codePoint in HANGUL_SYLLABLES
            else -> true
        }
    }

    private fun russianOf(letters: List<Int>): LanguageCode? {
        val cyrillic = letters.filter { UnicodeScript.of(it) == UnicodeScript.CYRILLIC }
        return LanguageCode.RU.takeIf { cyrillic.all { letter -> letter in RUSSIAN_LETTERS || letter in RUSSIAN_YO } }
    }

    private fun chineseOf(letters: List<Int>): LanguageCode? {
        val simplified = SIMPLIFIED.newEncoder()
        val traditional = TRADITIONAL.newEncoder()
        val japanese = JAPANESE.newEncoder()
        val hans = letters.filter { UnicodeScript.of(it) == UnicodeScript.HAN }.map { String(Character.toChars(it)) }
        if (hans.size < MIN_HAN_LETTERS) return null
        if (hans.any { !simplified.canEncode(it) && !traditional.canEncode(it) && japanese.canEncode(it) }) return null
        val simplifiedOnly = hans.count { simplified.canEncode(it) && !traditional.canEncode(it) }
        val traditionalOnly = hans.count { traditional.canEncode(it) && !simplified.canEncode(it) }
        return when {
            simplifiedOnly > 0 && traditionalOnly == 0 -> LanguageCode.ZH_HANS
            traditionalOnly > 0 && simplifiedOnly == 0 -> LanguageCode.ZH_HANT
            else -> null
        }
    }

    private fun latinOf(prose: String): LanguageCode? {
        val words = prose.split(WHITESPACE).filter { word -> word.any(Char::isLetter) }
        if (words.size < MIN_LATIN_WORDS) return null
        val vietnameseWords = words.count { word -> word.any(::isVietnameseOnlyLetter) }
        if (vietnameseWords.toDouble() / words.size >= VIETNAMESE_WORD_SHARE) return LanguageCode.VI
        if (words.size < MIN_LATIN_WORDS_FOR_MODEL) return null
        return LATIN_APP_LANGUAGES[latinDetector.detectLanguageOf(prose)]
    }

    private fun isVietnameseOnlyLetter(char: Char): Boolean = char in VIETNAMESE_TONE_LETTERS || char in VIETNAMESE_BASE_LETTERS

    private companion object {
        const val DOMINANT_SHARE = 0.7
        const val MIN_LATIN_WORDS = 2
        const val MIN_LATIN_WORDS_FOR_MODEL = 4
        const val MIN_HAN_LETTERS = 8
        const val VIETNAMESE_WORD_SHARE = 0.4

        val NOT_PROSE = Regex("https?://\\S+|[#@]\\S+")
        val WHITESPACE = Regex("\\s+")
        val HANGUL_SYLLABLES = 0xAC00..0xD7A3
        val VIETNAMESE_TONE_LETTERS = 'Ạ'..'ỹ'
        const val VIETNAMESE_BASE_LETTERS = "ăĂđĐơƠưƯ"

        val RUSSIAN_LETTERS = 0x0410..0x044F
        val RUSSIAN_YO = setOf(0x0401, 0x0451)

        val SIMPLIFIED: Charset = Charset.forName("GB2312")
        val TRADITIONAL: Charset = Charset.forName("Big5")
        val JAPANESE: Charset = Charset.forName("Shift_JIS")

        val LATIN_APP_LANGUAGES: Map<Language, LanguageCode> = mapOf(
            Language.ENGLISH to LanguageCode.EN,
            Language.SPANISH to LanguageCode.ES,
            Language.INDONESIAN to LanguageCode.ID,
            Language.MALAY to LanguageCode.ID,
        )
        val OTHER_LATIN_LANGUAGES: Set<Language> = setOf(
            Language.VIETNAMESE,
            Language.FRENCH,
            Language.PORTUGUESE,
            Language.GERMAN,
            Language.ITALIAN,
            Language.TAGALOG,
            Language.DUTCH,
            Language.TURKISH,
        )
    }
}
