package com.kbap.batch.notification

import com.kbap.common.domain.notification.NotificationJpaRepository
import com.kbap.common.domain.notification.model.NotificationType
import org.springframework.batch.infrastructure.item.ItemProcessor
import java.time.Clock

class ScanSuggestionSlotFilter(
    private val notificationRepository: NotificationJpaRepository,
    private val clock: Clock,
) : ItemProcessor<Long, Long> {
    override fun process(memberId: Long): Long? =
        memberId.takeUnless {
            notificationRepository.existsByMemberIdAndTypeAndCreatedAtGreaterThanEqual(
                it,
                NotificationType.SCAN_SUGGESTION,
                ScanSuggestionSendWindow.startOfCurrentSlot(clock),
            )
        }
}
