package com.kbap.app.batch.content

import com.kbap.domain.food.model.Food
import org.springframework.batch.infrastructure.item.ItemProcessor

// 음식 1건의 콘텐츠 4작업을 수행한다. LLM 등 외부 호출은 여기(트랜잭션 밖)서 일어나고,
// 저장·전이는 writer 가 completeContent 로 처리한다. 작업별로 메서드를 나눠 후속 태스크가
// 각자 자기 자리만 채운다 — 골격 단계에선 본문이 비어 있어 어떤 음식도 아직 완비되지 않는다.
class FoodContentItemProcessor : ItemProcessor<Food, ProcessedFood> {
    override fun process(item: Food): ProcessedFood {
        generateImage(item)
        generateDescription(item)
        translateNames(item)
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
    private fun translateNames(food: Food) {
    }

    // 기피성분 매핑·맵기 스텝 — KB-209 에서 API 3개 호출·종합으로 매핑을 쓰고 존재 여부를 반환한다.
    private fun mapAvoidance(food: Food): Boolean = false
}

data class ProcessedFood(
    val food: Food,
    val hasAvoidanceMapping: Boolean,
)
