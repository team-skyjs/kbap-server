package com.kbap.api.food

import com.kbap.common.domain.food.FoodViewLogJpaRepository
import com.kbap.common.domain.food.model.FoodViewLog
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class FoodViewLogListener(
    private val repository: FoodViewLogJpaRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async
    @EventListener
    @Transactional
    fun handle(event: FoodViewed) {
        try {
            repository.save(FoodViewLog(foodId = event.foodId, memberId = event.memberId))
        } catch (e: Exception) {
            log.error("음식 조회 이력 저장 실패 foodId={} memberId={}", event.foodId, event.memberId, e)
        }
    }
}
