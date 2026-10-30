package com.kbap.api.reviewbot

import com.kbap.common.domain.review.model.Review
import com.kbap.common.domain.review.model.ReviewBotForbiddenCategory

object ReviewBotContentGuard {
    val CATEGORIES: Set<ReviewBotForbiddenCategory> = ReviewBotForbiddenCategory.entries.toSet()

    val LANGUAGES: Set<String> = ReviewBotForbiddenCategory.LANGUAGES

    private val LINK: List<Regex> = listOf("http", "www.", "@", "#").map { Regex(Regex.escape(it), RegexOption.IGNORE_CASE) }

    private val FORBIDDEN: List<Regex> = CATEGORIES.flatMap { it.patterns() } + LINK

    private const val MIN_LENGTH = 20
    private const val MAX_LENGTH = 1000

    fun isAcceptable(text: String): Boolean =
        text.length in MIN_LENGTH..MAX_LENGTH &&
            text.trim().let { it.codePointCount(0, it.length) >= Review.MIN_CONTENT_LENGTH } &&
            FORBIDDEN.none { it.containsMatchIn(text) }

    private val CARD_LIMITS: Map<String, Int> = mapOf(
        "Korean" to 40,
        "Japanese" to 40,
        "Simplified Chinese" to 40,
        "Traditional Chinese" to 40,
        "Thai" to 60,
    )

    private const val LATIN_CARD_LIMIT = 80

    fun maxLengthFor(language: String): Int = CARD_LIMITS[language] ?: LATIN_CARD_LIMIT

    fun fitsCard(text: String, language: String): Boolean =
        text.trim().let { it.codePointCount(0, it.length) <= maxLengthFor(language) }

    fun coverage(language: String): Map<ReviewBotForbiddenCategory, List<String>> =
        CATEGORIES.associateWith { it.terms(language) }
}
