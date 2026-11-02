package com.kbap.api.processedfood

import com.kbap.api.translation.FakeTextTranslator
import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.translation.ContentTranslationJpaRepository
import com.kbap.common.port.llm.ProcessedFoodTextTranslator
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.SimpleTransactionStatus

class ProcessedFoodTranslationBudgetTest : BehaviorSpec({
    given("가공식품 번역 일일 상한 계수") {
        `when`("Redis 가 실패하면") {
            then("엔진을 부르지 않고 원문을 둔다") {
                val translator = FakeTextTranslator()
                @Suppress("UNCHECKED_CAST")
                val provider = mock(ObjectProvider::class.java) as ObjectProvider<ProcessedFoodTextTranslator>
                `when`(provider.getIfAvailable()).thenReturn(translator)
                val redis = mock(StringRedisTemplate::class.java)
                `when`(redis.opsForValue()).thenThrow(RedisConnectionFailureException("down"))
                val transactions = mock(PlatformTransactionManager::class.java)
                `when`(transactions.getTransaction(any())).thenReturn(SimpleTransactionStatus())
                val service = ProcessedFoodTranslationService(
                    mock(ContentTranslationJpaRepository::class.java), provider, ProcessedFoodFeaturedCatalog(),
                    mock(ProcessedFoodService::class.java), redis, transactions,
                    dailyBudgetKrw = 5000.0, estimatedKrwPerCall = 0.4, syncDeadlineMillis = 3000L,
                )
                val item = ProcessedFoodSummaryResponse(1L, "R-1", "동네과자", "㈜동네식품", "과자", null, labelKnown = true)

                service.localizeSummaries(listOf(item), LanguageCode.EN).single().name shouldBe "동네과자"
                service.awaitIdle()

                translator.calls.size shouldBe 0
                service.localizeSummaries(listOf(item), LanguageCode.EN).single().name shouldBe "동네과자"
            }
        }
    }
})
