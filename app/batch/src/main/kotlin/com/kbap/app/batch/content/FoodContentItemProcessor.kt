package com.kbap.app.batch.content

import com.kbap.core.food.FoodAvoidanceAssessmentClient
import com.kbap.domain.food.FoodJpaRepository
import com.kbap.domain.food.model.Food
import com.kbap.domain.food.model.FoodAvoidanceItem
import org.springframework.batch.infrastructure.item.ItemProcessor
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

// 작업 본문은 골격 단계에선 비어 있다 — KB-183(설명·번역)·KB-184(사진)·KB-209(기피성분)가 채운다.
class FoodContentItemProcessor(
    private val foodRepository: FoodJpaRepository,
    transactionManager: PlatformTransactionManager,
    private val avoidanceClient: FoodAvoidanceAssessmentClient,
    // 매 호출마다 평가 — 카탈로그는 고정 참조 데이터라 조회 비용이 작고, 시드 시점 의존이 없다.
    private val candidateCodes: () -> Set<String>,
) : ItemProcessor<Food, Food> {
    // REQUIRES_NEW — 청크 트랜잭션과 분리해 즉시 커밋. 뒤 작업이 실패해도 이 작업 결과는 유지(재실행 시 실패 작업만 재시도).
    private val progressTransaction = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    override fun process(item: Food): Food {
        if (item.needsImage()) {
            generateImage(item)
            saveProgress(item)
        }
        if (item.needsDescription()) {
            generateDescription(item)
            saveProgress(item)
        }
        if (item.needsNameTranslations() || item.needsDescriptionTranslations()) {
            translateContent(item)
            saveProgress(item)
        }
        if (item.needsAvoidanceMapping()) {
            mapAvoidance(item)
            saveProgress(item)
        }
        return item
    }

    fun saveProgress(food: Food) {
        progressTransaction.executeWithoutResult { foodRepository.save(food) }
    }

    private fun generateImage(food: Food) {
    }

    private fun generateDescription(food: Food) {
    }

    private fun translateContent(food: Food) {
    }

    // 조사·종합(3모델 합의·미지코드 폐기)은 client 구현(:infra:llm) 책임 — 결과 code 는 candidateCodes 소속을 보장한다.
    private fun mapAvoidance(food: Food) {
        val codes = candidateCodes()
        if (codes.isEmpty()) return
        val substances = avoidanceClient.call(food.koreanName, codes)
            .map { FoodAvoidanceItem(code = it.code, inclusionPercent = it.inclusionPercent) }
        food.assessAvoidance(substances)
    }
}
