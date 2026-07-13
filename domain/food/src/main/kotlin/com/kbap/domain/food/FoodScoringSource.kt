package com.kbap.domain.food

import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service

// 배치 스코어링의 음식 청크 공급자 — id 오름차순 페이지 단위로 전체 대기열을 소진한다.
// 배치 컨텍스트는 도메인 서비스 그래프(FoodService→MemberService→소셜 seam)를 올리지 않으므로
// 레포지토리만 무는 전용 소형 서비스로 분리한다(@Import 로 조립).
@Service
class FoodScoringSource internal constructor(
    private val foodRepository: FoodJpaRepository,
) {
    fun nextChunk(page: Int, size: Int): List<Food> =
        foodRepository.findFoodIds(PageRequest.of(page, size))
            .takeIf { it.isNotEmpty() }
            ?.let { ids -> foodRepository.findByIdIn(ids).sortedBy { it.id } }
            .orEmpty()
}
