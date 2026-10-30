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

    fun maxLengthFor(language: String): Int = 80

    fun fitsCard(text: String, language: String): Boolean = true

    fun coverage(language: String): Map<ReviewBotForbiddenCategory, List<String>> =
        CATEGORIES.associateWith { it.terms(language) }
}
