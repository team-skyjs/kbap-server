package com.kbap.app.batch.content

import com.kbap.domain.food.FoodJpaRepository
import com.kbap.domain.food.model.Food
import com.kbap.domain.food.model.FoodContentStatus
import org.springframework.batch.infrastructure.item.Chunk
import org.springframework.batch.infrastructure.item.ItemWriter

// 콘텐츠 채우기는 프로세서가 작업별로 이미 커밋했다 — writer 는 완비 판정 → 검수 대기 전환만 담당한다.
// 리더가 넘긴 엔티티는 detached 라 save(merge) 는 건당 SELECT 를 유발한다 — 전환 대상만 모아
// 단일 벌크 UPDATE 로 친다. 미전환 건은 DB 접근이 아예 없다.
class FoodContentItemWriter(
    private val foodRepository: FoodJpaRepository,
) : ItemWriter<Food> {

    override fun write(chunk: Chunk<out Food>) {
        val completed = chunk.items.filter {
            it.transitionToPendingReviewIfComplete() && it.contentStatus == FoodContentStatus.PENDING_REVIEW
        }
        if (completed.isEmpty()) return
        foodRepository.updateContentStatusByIdIn(completed.map { it.id }, FoodContentStatus.PENDING_REVIEW)
    }
}
