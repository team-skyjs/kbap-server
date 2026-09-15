package com.kbap.batch.notification

import com.kbap.common.domain.notification.PushRequest
import com.kbap.common.domain.notification.model.MealSlot
import com.kbap.common.domain.notification.model.NotificationType
import com.kbap.common.port.push.PushNotifier
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.batch.infrastructure.item.Chunk
import org.springframework.batch.infrastructure.item.ItemWriter

class ScanSuggestionPushWriter(
    private val notifier: PushNotifier,
    private val slot: MealSlot,
    private val ttlSeconds: Int,
    private val meterRegistry: MeterRegistry,
) : ItemWriter<Long> {
    override fun write(chunk: Chunk<out Long>) {
        val memberIds = chunk.items.toList()
        val result = notifier.send(PushRequest(NotificationType.SCAN_SUGGESTION, memberIds, ttlSeconds = ttlSeconds, mealSlot = slot))
        meterRegistry.counter(METRIC, "type", NotificationType.SCAN_SUGGESTION.name, "result", "sent").increment(result.sent.toDouble())
        meterRegistry.counter(METRIC, "type", NotificationType.SCAN_SUGGESTION.name, "result", "failed").increment(result.failed.toDouble())
        logger.info("스캔 제안 발송 slot={} members={} sent={} failed={}", slot, memberIds.size, result.sent, result.failed)
    }

    companion object {
        const val METRIC = "kbap.push.dispatch"

        private val logger = LoggerFactory.getLogger(ScanSuggestionPushWriter::class.java)
    }
}
