package com.kbap.batch.notification.reminder

import com.kbap.common.domain.order.OrderJpaRepository
import com.kbap.common.domain.order.model.Order
import org.springframework.batch.infrastructure.item.ExecutionContext
import org.springframework.batch.infrastructure.item.ItemStreamReader
import org.springframework.data.domain.Limit
import java.time.Clock
import java.time.LocalDateTime

class ReviewReminderOrderReader(
    private val orderRepository: OrderJpaRepository,
    private val clock: Clock,
    private val pageSize: Int,
) : ItemStreamReader<Order> {
    private var cursor = 0L
    private var window: ClosedRange<LocalDateTime> = LocalDateTime.MIN..LocalDateTime.MIN
    private val page = ArrayDeque<Order>()

    override fun open(executionContext: ExecutionContext) {
        cursor = 0L
        window = ReviewReminderWindow.of(clock)
        page.clear()
    }

    override fun read(): Order? {
        if (page.isEmpty()) {
            page.addAll(
                orderRepository.findReviewReminderTargets(
                    window.start,
                    window.endInclusive,
                    cursor,
                    Limit.of(pageSize),
                ),
            )
            cursor = page.lastOrNull()?.id ?: cursor
        }
        return page.removeFirstOrNull()
    }
}
