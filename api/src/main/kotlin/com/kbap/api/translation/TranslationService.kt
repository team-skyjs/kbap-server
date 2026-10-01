package com.kbap.api.translation

import com.kbap.api.review.ReviewService
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.translation.ContentTranslationJpaRepository
import com.kbap.common.domain.translation.model.TranslationTargetType
import com.kbap.common.port.llm.TextTranslator
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.security.MessageDigest
import java.time.LocalDateTime

@Service
class TranslationService(
    private val reviewService: ReviewService,
    private val translationRepository: ContentTranslationJpaRepository,
    private val translator: TextTranslator,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val readTransaction = TransactionTemplate(transactionManager).apply { isReadOnly = true }
    private val writeTransaction = TransactionTemplate(transactionManager)

    fun translate(viewerMemberId: Long?, targetType: TranslationTargetType, targetId: Long, language: LanguageCode): String {
        val source = sourceOf(viewerMemberId, targetType, targetId)
        if (source.isBlank()) return ""
        val sourceHash = hashOf(source)
        val cached = readTransaction.execute {
            translationRepository.findByTargetTypeAndTargetIdAndLanguage(targetType, targetId, language.code)
        }
        if (cached != null && cached.sourceHash == sourceHash) return cached.translatedText

        val translated = translateOrFail(source, targetType, targetId, language)
        store(targetType, targetId, language, sourceHash, translated)
        return translated
    }

    private fun sourceOf(viewerMemberId: Long?, targetType: TranslationTargetType, targetId: Long): String =
        when (targetType) {
            TranslationTargetType.REVIEW -> reviewService.getVisibleReview(viewerMemberId, targetId).content.orEmpty()
        }

    private fun translateOrFail(source: String, targetType: TranslationTargetType, targetId: Long, language: LanguageCode): String {
        val translated = try {
            translator.translate(source, language)
        } catch (e: RuntimeException) {
            log.warn("번역 엔진 호출 실패 — targetType={}, targetId={}, language={}", targetType, targetId, language.code, e)
            throw BusinessException(ErrorCode.TRANSLATION_FAILED)
        }
        if (translated.isBlank() || translated.length > MAX_TRANSLATED_LENGTH) {
            log.warn(
                "번역 결과를 쓸 수 없다(빈 문자열 또는 상한 초과) — targetType={}, targetId={}, language={}, length={}",
                targetType, targetId, language.code, translated.length,
            )
            throw BusinessException(ErrorCode.TRANSLATION_FAILED)
        }
        return translated
    }

    private fun store(targetType: TranslationTargetType, targetId: Long, language: LanguageCode, sourceHash: String, translated: String) {
        try {
            writeTransaction.executeWithoutResult {
                translationRepository.upsert(targetType.name, targetId, language.code, sourceHash, translated, LocalDateTime.now())
            }
        } catch (e: RuntimeException) {
            log.warn("번역 캐시 저장 실패 — 번역문은 돌려주고 다음 요청이 다시 번역한다: targetType={}, targetId={}, language={}", targetType, targetId, language.code, e)
        }
    }

    private fun hashOf(source: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$CACHE_VERSION\n$source".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        const val CACHE_VERSION = "1"
        const val MAX_TRANSLATED_LENGTH = 10_000
    }
}
