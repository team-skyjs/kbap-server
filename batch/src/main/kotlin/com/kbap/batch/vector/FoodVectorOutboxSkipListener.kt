package com.kbap.batch.vector

import com.kbap.common.domain.food.FoodVectorOutboxJpaRepository
import com.kbap.common.domain.food.model.FoodVectorOutbox
import org.slf4j.LoggerFactory
import org.springframework.batch.core.listener.SkipListener

class FoodVectorOutboxSkipListener(
    private val outboxRepository: FoodVectorOutboxJpaRepository,
) : SkipListener<FoodVectorOutbox, FoodVectorOutbox> {
    override fun onSkipInProcess(item: FoodVectorOutbox, t: Throwable) = recordFailure(item, t)

    override fun onSkipInWrite(item: FoodVectorOutbox, t: Throwable) = recordFailure(item, t)

    private fun recordFailure(item: FoodVectorOutbox, throwable: Throwable) {
        val outbox = outboxRepository.findById(item.id).orElse(null) ?: return
        outbox.recordFailure(throwable.message ?: throwable.javaClass.simpleName)
        logger.warn(
            "음식 벡터 동기화 재시도 소진 outboxId={} foodId={} attempts={}",
            item.id,
            item.foodId,
            outbox.attempts,
            throwable,
        )
    }

    private companion object {
        val logger = LoggerFactory.getLogger(FoodVectorOutboxSkipListener::class.java)
    }
}
