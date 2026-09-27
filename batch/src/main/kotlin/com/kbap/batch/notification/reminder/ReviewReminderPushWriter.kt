package com.kbap.batch.notification.reminder

import com.kbap.batch.notification.PushDispatchMetric
import com.kbap.common.domain.notification.PushRequest
import com.kbap.common.domain.notification.model.Notification
import com.kbap.common.domain.notification.model.NotificationType
import com.kbap.common.domain.order.model.Order
import com.kbap.common.port.push.PushHandler
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.batch.infrastructure.item.Chunk
import org.springframework.batch.infrastructure.item.ItemWriter

class ReviewReminderPushWriter(
    private val handler: PushHandler,
    private val ttlSeconds: Int,
    private val meterRegistry: MeterRegistry,
) : ItemWriter<Order> {
    override fun write(chunk: Chunk<out Order>) {
        var sent = 0
        var failed = 0
        val orders = chunk.items.distinctBy { it.memberId }
        orders.forEach { order ->
            val result = handler.send(
                PushRequest(
                    NotificationType.REVIEW_REMINDER,
                    listOf(order.memberId),
                    data = mapOf(Notification.DATA_ORDER_ID to order.id),
                    ttlSeconds = ttlSeconds,
                ),
            )
            sent += result.sent
            failed += result.failed
        }
        meterRegistry.counter(PushDispatchMetric.NAME, "type", NotificationType.REVIEW_REMINDER.name, "result", "sent").increment(sent.toDouble())
        meterRegistry.counter(PushDispatchMetric.NAME, "type", NotificationType.REVIEW_REMINDER.name, "result", "failed").increment(failed.toDouble())
        logger.info("리뷰 리마인더 발송 orders={} members={} sent={} failed={}", chunk.size(), orders.size, sent, failed)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ReviewReminderPushWriter::class.java)
    }
}
