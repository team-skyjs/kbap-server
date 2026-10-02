package com.kbap.api.translation

import com.kbap.api.review.ReviewService
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.translation.ContentTranslationJpaRepository
import com.kbap.common.domain.translation.model.TranslationTargetType
import com.kbap.common.port.llm.TextTranslator
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.concurrent.Semaphore
import javax.sql.DataSource

@Service
class TranslationService(
    private val reviewService: ReviewService,
    private val translationRepository: ContentTranslationJpaRepository,
    private val translatorProvider: ObjectProvider<TextTranslator>,
    transactionManager: PlatformTransactionManager,
    dataSource: DataSource,
    @Value("\${kbap.translation.max-concurrent-engine-calls:4}") configuredMaxConcurrentEngineCalls: Int,
) {
    val maxConcurrentEngineCalls: Int = clampToPool(configuredMaxConcurrentEngineCalls, dataSource)

    private val enginePermits = Semaphore(maxConcurrentEngineCalls)

    private val log = LoggerFactory.getLogger(javaClass)
    private val readTransaction = TransactionTemplate(transactionManager).apply { isReadOnly = true }
    private val writeTransaction = TransactionTemplate(transactionManager)

    fun translate(viewerMemberId: Long?, targetType: TranslationTargetType, targetId: Long, language: LanguageCode): TranslatedContent {
        val source = sourceOf(viewerMemberId, targetType, targetId)
        if (source.isBlank()) return TranslatedContent("", null)
        val sourceHash = hashOf(source)
        val cached = readTransaction.execute {
            translationRepository.findByTargetTypeAndTargetIdAndLanguage(targetType, targetId, language.code)
        }
        if (cached != null && cached.sourceHash == sourceHash) return TranslatedContent(cached.translatedText, cached.sourceLanguage)

        val translated = TranslatedContent(translateOrFail(source, targetType, targetId, language), null)
        store(targetType, targetId, language, sourceHash, translated)
        return translated
    }

    private fun sourceOf(viewerMemberId: Long?, targetType: TranslationTargetType, targetId: Long): String =
        when (targetType) {
            TranslationTargetType.REVIEW -> reviewService.getVisibleReview(viewerMemberId, targetId).content.orEmpty()
        }

    private fun translateOrFail(source: String, targetType: TranslationTargetType, targetId: Long, language: LanguageCode): String {
        val translator = translatorProvider.getIfAvailable() ?: run {
            log.warn("번역 엔진이 꺼져 있다(kbap.llm.translation.enabled=false) — 번역 요청을 거절한다")
            throw BusinessException(ErrorCode.TRANSLATION_FAILED)
        }
        if (!enginePermits.tryAcquire()) {
            log.warn("동시 번역 엔진 호출 상한({})에 닿아 거절한다 — targetType={}, targetId={}, language={}", maxConcurrentEngineCalls, targetType, targetId, language.code)
            throw BusinessException(ErrorCode.TRANSLATION_FAILED, expected = true)
        }
        val translated = try {
            translator.translate(source, language).text
        } catch (e: RuntimeException) {
            log.warn("번역 엔진 호출 실패 — targetType={}, targetId={}, language={}", targetType, targetId, language.code, e)
            throw BusinessException(ErrorCode.TRANSLATION_FAILED)
        } finally {
            enginePermits.release()
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

    private fun store(targetType: TranslationTargetType, targetId: Long, language: LanguageCode, sourceHash: String, translated: TranslatedContent) {
        try {
            writeTransaction.executeWithoutResult {
                translationRepository.upsert(targetType.name, targetId, language.code, sourceHash, translated.text, translated.sourceLanguage, LocalDateTime.now())
            }
        } catch (e: RuntimeException) {
            log.warn("번역 캐시 저장 실패 — 번역문은 돌려주고 다음 요청이 다시 번역한다: targetType={}, targetId={}, language={}", targetType, targetId, language.code, e)
        }
    }

    private fun hashOf(source: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$CACHE_VERSION\n$source".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun clampToPool(configured: Int, dataSource: DataSource): Int {
        val poolSize = runCatching { dataSource.unwrap(HikariDataSource::class.java).maximumPoolSize }.getOrNull() ?: return configured
        val ceiling = (poolSize / 2).coerceAtLeast(1)
        if (configured <= ceiling) return configured
        LoggerFactory.getLogger(javaClass).warn(
            "kbap.translation.max-concurrent-engine-calls={} 가 DB 커넥션 풀({})의 절반을 넘어 {} 로 낮춘다 — 번역이 풀을 다 쓰지 못하게 한다",
            configured, poolSize, ceiling,
        )
        return ceiling
    }

    companion object {
        const val CACHE_VERSION = "1"
        const val MAX_TRANSLATED_LENGTH = 10_000
    }
}

data class TranslatedContent(val text: String, val sourceLanguage: String?)
