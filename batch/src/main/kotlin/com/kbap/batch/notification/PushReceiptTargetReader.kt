package com.kbap.batch.notification

import com.kbap.common.domain.notification.NotificationDispatchJpaRepository
import com.kbap.common.domain.notification.model.NotificationDispatch
import com.kbap.common.domain.notification.model.NotificationType
import org.springframework.batch.infrastructure.item.ExecutionContext
import org.springframework.batch.infrastructure.item.ItemStreamReader
import org.springframework.data.domain.Limit
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId

class PushReceiptTargetReader(
    private val dispatchRepository: NotificationDispatchJpaRepository,
    private val types: List<NotificationType>,
    private val clock: Clock,
    private val minAge: Duration,
    private val maxAge: Duration,
    private val pageSize: Int,
) : ItemStreamReader<NotificationDispatch> {
    private var cursor = 0L
    private var now = LocalDateTime.MIN
    private val page = ArrayDeque<NotificationDispatch>()

    override fun open(executionContext: ExecutionContext) {
        cursor = 0L
        now = LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault())
        page.clear()
    }

    override fun read(): NotificationDispatch? {
        if (page.isEmpty()) {
            page.addAll(dispatchRepository.findSentForReceiptCheck(types, now - maxAge, now - minAge, cursor, Limit.of(pageSize)))
            cursor = page.lastOrNull()?.id ?: cursor
        }
        return page.removeFirstOrNull()
    }
}
