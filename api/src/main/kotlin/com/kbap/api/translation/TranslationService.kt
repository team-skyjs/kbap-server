package com.kbap.api.translation

import com.kbap.api.review.ReviewService
import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.translation.ContentTranslationJpaRepository
import com.kbap.common.domain.translation.model.TranslationTargetType
import com.kbap.common.port.llm.TextTranslator
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager

@Service
class TranslationService(
    private val reviewService: ReviewService,
    private val translationRepository: ContentTranslationJpaRepository,
    private val translator: TextTranslator,
    transactionManager: PlatformTransactionManager,
) {
    fun translate(viewerMemberId: Long?, targetType: TranslationTargetType, targetId: Long, language: LanguageCode): String = ""

    companion object {
        const val CACHE_VERSION = "1"
        const val MAX_TRANSLATED_LENGTH = 10_000
    }
}
