package com.kbap.app.batch.content

import com.kbap.domain.food.FoodJpaRepository
import com.kbap.domain.food.model.Food
import org.springframework.batch.infrastructure.item.Chunk
import org.springframework.batch.infrastructure.item.ItemWriter

// 콘텐츠 채우기는 프로세서가 작업별로 이미 커밋했다 — writer 는 완비 판정 → 검수 대기 전환만 담당한다.
class FoodContentItemWriter(
    private val foodRepository: FoodJpaRepository,
) : ItemWriter<Food> {

    override fun write(chunk: Chunk<out Food>) {
        chunk.items.forEach { food ->
            food.transitionToPendingReviewIfComplete()
            foodRepository.save(food)
        }
    }
}
