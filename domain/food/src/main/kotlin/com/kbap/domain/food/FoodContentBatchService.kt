package com.kbap.domain.food

import com.kbap.domain.food.model.Food
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

// 배치 콘텐츠 파이프라인의 음식 창구(KB-182) — INCOMPLETE 음식을 id 키셋으로 청크 공급하고,
// 작업별 부분 진행을 즉시 커밋(saveProgress)하며, 4작업 완비 시 READY 로 전이한다.
// 배치는 도메인 서비스 그래프를 올리지 않으므로 레포지토리만 무는 전용 소형 서비스로 두고 @Import 로 조립한다.
@Service
class FoodContentBatchService internal constructor(
    private val foodRepository: FoodJpaRepository,
) {
    @Transactional(readOnly = true)
    fun getIncompleteFoods(afterId: Long?, size: Int): List<Food> =
        foodRepository.findIncompleteAfter(afterId, PageRequest.of(0, size))

    // 한 작업의 결과를 독립 트랜잭션으로 즉시 커밋한다 — 뒤 작업이 실패해도 앞 작업 결과가 롤백되지 않아,
    // 다음 실행에서 이미 된 작업을 건너뛰고(needsX=false) 실패한 작업만 재시도한다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun saveProgress(food: Food) {
        foodRepository.save(food)
    }

    @Transactional
    fun completeContent(food: Food, hasAvoidanceMapping: Boolean): Boolean {
        val transitioned = food.transitionToReadyIfComplete(hasAvoidanceMapping)
        foodRepository.save(food)
        return transitioned
    }
}
