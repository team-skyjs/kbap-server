package com.kbap.batch.notification.receipt

import com.kbap.batch.notification.nowInJvmZone
import com.kbap.common.domain.notification.PushDispatchService
import com.kbap.common.domain.notification.PushOutcome
import com.kbap.common.domain.notification.PushReceiptService
import com.kbap.common.domain.notification.PreparedPush
import com.kbap.common.domain.notification.ReceiptOutcome
import com.kbap.common.domain.notification.ResendPolicy
import com.kbap.common.domain.notification.model.NotificationDispatch
import com.kbap.common.port.push.PushMessage
import com.kbap.common.port.push.PushReceiptClient
import com.kbap.common.port.push.PushSender
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.batch.infrastructure.item.Chunk
import org.springframework.batch.infrastructure.item.ItemWriter
import java.time.Clock

class PushReceiptSyncWriter(
    private val receiptClient: PushReceiptClient,
    private val receiptService: PushReceiptService,
    private val dispatchService: PushDispatchService,
    private val sender: PushSender,
    private val policy: ResendPolicy,
    private val clock: Clock,
    private val meterRegistry: MeterRegistry,
) : ItemWriter<NotificationDispatch> {
    override fun write(chunk: Chunk<out NotificationDispatch>) {
        val dispatches = chunk.items
        val receipts = try {
            receiptClient.fetch(dispatches.mapNotNull { it.ticketId })
        } catch (e: RuntimeException) {
            logger.warn("Expo 영수증 조회 실패 — 다음 회차에 다시 시도합니다 dispatches={}", dispatches.size, e)
            return
        }
        val outcomes = dispatches
            .mapNotNull { dispatch -> receipts[dispatch.ticketId]?.let { dispatch.id to ReceiptOutcome(it.ok, it.errorCode, it.message) } }
            .toMap()
        val applied = receiptService.apply(outcomes, policy, clock.nowInJvmZone())
        resend(applied.resend)

        applied.results.groupingBy { it }.eachCount().forEach { (key, count) ->
            meterRegistry.counter(METRIC, "type", key.first.name, "result", key.second.name.lowercase()).increment(count.toDouble())
        }
        dispatches.filter { it.id !in outcomes }.groupingBy { it.notificationType }.eachCount().forEach { (type, count) ->
            meterRegistry.counter(METRIC, "type", type?.name ?: "UNKNOWN", "result", "pending").increment(count.toDouble())
        }
        logger.info("푸시 영수증 확인 dispatches={} receipts={} results={}", dispatches.size, outcomes.size, applied.results.groupingBy { it.second }.eachCount())
    }

    private fun resend(prepared: PreparedPush) {
        if (prepared.isEmpty()) return
        val tickets = sender.send(prepared.messages.map { PushMessage(it.to, it.title, it.body, it.data, channelId = it.channelId, ttlSeconds = it.ttlSeconds) })
        dispatchService.record(prepared, tickets.map { PushOutcome(it.ok, it.id, it.error) })
    }

    companion object {
        const val METRIC = "kbap.push.receipt"

        private val logger = LoggerFactory.getLogger(PushReceiptSyncWriter::class.java)
    }
}
