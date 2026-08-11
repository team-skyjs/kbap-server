package com.kbap.infra.mq

import com.fasterxml.jackson.databind.ObjectMapper
import com.kbap.common.port.mq.FoodContentEvent
import com.kbap.common.port.mq.FoodContentEventPublisher
import com.kbap.common.port.mq.FoodContentPublishResult
import org.slf4j.LoggerFactory
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequest
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry

class SqsFoodContentEventPublisher(
    private val sqsClient: SqsClient,
    private val objectMapper: ObjectMapper,
    private val queueUrl: String,
) : FoodContentEventPublisher {
    override fun publish(events: List<FoodContentEvent>): FoodContentPublishResult {
        val succeeded = mutableSetOf<Long>()
        val failed = mutableSetOf<Long>()

        events.chunked(SQS_BATCH_LIMIT).forEach { batch ->
            val requestedIds = batch.mapTo(mutableSetOf()) { it.outboxId }
            try {
                val response = sqsClient.sendMessageBatch(
                    SendMessageBatchRequest.builder()
                        .queueUrl(queueUrl)
                        .entries(
                            batch.map {
                                SendMessageBatchRequestEntry.builder()
                                    .id(it.outboxId.toString())
                                    .messageBody(objectMapper.writeValueAsString(it))
                                    .build()
                            },
                        )
                        .build(),
                )
                val succeededIds = response.successful().mapNotNullTo(mutableSetOf()) { it.id().toLongOrNull() }
                succeeded += succeededIds.intersect(requestedIds)
                failed += requestedIds - succeededIds
            } catch (exception: Exception) {
                logger.warn("음식 콘텐츠 SQS 묶음 발행에 실패했습니다. outboxIds={}", requestedIds, exception)
                failed += requestedIds
            }
        }

        return FoodContentPublishResult(
            succeededOutboxIds = succeeded,
            failedOutboxIds = failed,
        )
    }

    private companion object {
        const val SQS_BATCH_LIMIT = 10
        val logger = LoggerFactory.getLogger(SqsFoodContentEventPublisher::class.java)
    }
}
