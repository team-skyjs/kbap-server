package com.kbap.api.notification

import com.kbap.api.review.ReviewLiked
import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.notification.PushRequest
import com.kbap.common.domain.notification.model.NotificationType
import com.kbap.common.port.push.PushHandler
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

@Component
class HelpfulPushListener(
    private val pushHandler: PushHandler,
    private val foodRepository: FoodJpaRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun handle(event: ReviewLiked) {
        try {
            val food = foodRepository.findByIdOrNull(event.foodId) ?: return
            val argsByLang = LanguageCode.entries.associateWith { mapOf("food" to food.displayName(it)) }
            val result = pushHandler.send(
                PushRequest(
                    NotificationType.HELPFUL,
                    listOf(event.authorMemberId),
                    argsByLang = argsByLang,
                    data = mapOf("reviewId" to event.reviewId),
                ),
            )
            log.info("HELPFUL 발송 reviewId={} sent={} failed={}", event.reviewId, result.sent, result.failed)
        } catch (e: Exception) {
            log.error("HELPFUL 발송 실패 reviewId={} authorMemberId={}", event.reviewId, event.authorMemberId, e)
        }
    }
}
