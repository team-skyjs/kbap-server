package com.kbap.batch.notification

import com.kbap.common.domain.notification.NotificationSettingJpaRepository
import com.kbap.common.domain.notification.model.NotificationType
import org.springframework.batch.infrastructure.item.ExecutionContext
import org.springframework.batch.infrastructure.item.ItemStreamReader
import org.springframework.data.domain.Limit
import java.time.Clock
import java.time.LocalDateTime

class ScanSuggestionMemberIdReader(
    private val settingRepository: NotificationSettingJpaRepository,
    private val clock: Clock,
    private val pageSize: Int,
) : ItemStreamReader<Long> {
    private var cursor = 0L
    private var slotStart = LocalDateTime.MIN
    private val page = ArrayDeque<Long>()

    override fun open(executionContext: ExecutionContext) {
        cursor = 0L
        slotStart = ScanSuggestionSendWindow.startOfCurrentSlot(clock)
        page.clear()
    }

    override fun read(): Long? {
        if (page.isEmpty()) {
            page.addAll(
                settingRepository.findNewsMemberIdsNotNotifiedSince(NotificationType.SCAN_SUGGESTION, slotStart, cursor, Limit.of(pageSize)),
            )
            cursor = page.lastOrNull() ?: cursor
        }
        return page.removeFirstOrNull()
    }
}
