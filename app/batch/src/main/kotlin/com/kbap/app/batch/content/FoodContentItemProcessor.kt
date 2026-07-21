package com.kbap.app.batch.content

import com.kbap.domain.food.FoodContentBatchService
import com.kbap.domain.food.model.Food
import org.springframework.batch.infrastructure.item.ItemProcessor

// 음식 1건의 콘텐츠 4작업을 수행한다. 각 작업은 "이미 됐으면 건너뛰고(needsX=false), 아니면 LLM 호출 후 즉시 커밋".
// 덕분에 해야 하는 음식만 LLM 을 태우고, 뒤 작업이 실패해도 앞 작업은 남아 다음 실행에서 실패한 작업만 재시도한다.
// 저장·전이는 saveProgress(작업별 독립 커밋)와 writer 의 completeContent(전이)로 나뉜다.
// 작업 본문은 골격 단계에선 비어 있어 어떤 음식도 아직 완비되지 않는다(KB-183·184·209 가 채운다).
class FoodContentItemProcessor(
    private val foodContentBatchService: FoodContentBatchService,
) : ItemProcessor<Food, ProcessedFood> {
    override fun process(item: Food): ProcessedFood {
        if (item.needsImage()) {
            generateImage(item)
            foodContentBatchService.saveProgress(item)
        }
        if (item.needsDescription()) {
            generateDescription(item)
            foodContentBatchService.saveProgress(item)
        }
        if (item.needsNameTranslations() || item.needsDescriptionTranslations()) {
            translateContent(item)
            foodContentBatchService.saveProgress(item)
        }
        val hasAvoidanceMapping = mapAvoidance(item)
        return ProcessedFood(item, hasAvoidanceMapping)
    }

    // 사진 생성 스텝 — KB-184 에서 imageRef 를 채운다.
    private fun generateImage(food: Food) {
    }

    // 설명 생성 스텝 — KB-183 에서 ko description 을 채운다.
    private fun generateDescription(food: Food) {
    }

    // 이름·설명 번역 스텝 — KB-183 에서 9개 대상 언어 번역을 채운다.
    private fun translateContent(food: Food) {
    }

    // 기피성분 매핑·맵기 스텝 — KB-209 에서 이미 매핑이 있으면 건너뛰고, 없으면 API 3개 호출·종합으로
    // 매핑을 쓴 뒤 존재 여부를 반환한다.
    private fun mapAvoidance(food: Food): Boolean = false
}

data class ProcessedFood(
    val food: Food,
    val hasAvoidanceMapping: Boolean,
)
