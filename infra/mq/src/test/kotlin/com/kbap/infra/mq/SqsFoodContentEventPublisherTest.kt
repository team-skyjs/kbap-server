package com.kbap.infra.mq

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.common.port.mq.FoodContentEvent
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.BatchResultErrorEntry
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequest
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResponse
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResultEntry

class SqsFoodContentEventPublisherTest : BehaviorSpec({
    val mapper = jacksonObjectMapper()

    fun events(count: Int): List<FoodContentEvent> = (1..count).map {
        FoodContentEvent(outboxId = it.toLong(), foodId = (100 + it).toLong(), scannedName = "스캔-$it")
    }

    fun success(ids: List<String>): SendMessageBatchResponse = SendMessageBatchResponse.builder()
        .successful(ids.map { SendMessageBatchResultEntry.builder().id(it).messageId("message-$it").build() })
        .failed(emptyList())
        .build()

    given("음식 콘텐츠 이벤트 발행") {
        `when`("11건 이상을 발행하면") {
            then("SQS 제한에 맞춰 10건씩 나누고 계약 JSON을 보낸다") {
                val sqsClient = mock(SqsClient::class.java)
                `when`(sqsClient.sendMessageBatch(any(SendMessageBatchRequest::class.java)))
                    .thenReturn(success((1..10).map(Int::toString)), success(listOf("11")))
                val publisher = SqsFoodContentEventPublisher(sqsClient, mapper, "queue-url")

                val result = publisher.publish(events(11))

                val captor = ArgumentCaptor.forClass(SendMessageBatchRequest::class.java)
                verify(sqsClient, org.mockito.Mockito.times(2)).sendMessageBatch(captor.capture())
                captor.allValues.map { it.entries().size } shouldContainExactly listOf(10, 1)
                mapper.readTree(captor.allValues.first().entries().first().messageBody()) shouldBe mapper.readTree(
                    """{"outboxId":1,"foodId":101,"scannedName":"스캔-1"}""",
                )
                result.succeededOutboxIds shouldBe (1L..11L).toSet()
                result.failedOutboxIds shouldBe emptySet()
            }
        }

        `when`("SQS가 일부 성공과 실패만 응답하면") {
            then("응답에 없는 항목도 실패로 판정한다") {
                val sqsClient = mock(SqsClient::class.java)
                val response = SendMessageBatchResponse.builder()
                    .successful(SendMessageBatchResultEntry.builder().id("1").messageId("message-1").build())
                    .failed(BatchResultErrorEntry.builder().id("2").code("InternalError").message("실패").build())
                    .build()
                `when`(sqsClient.sendMessageBatch(any(SendMessageBatchRequest::class.java))).thenReturn(response)
                val publisher = SqsFoodContentEventPublisher(sqsClient, mapper, "queue-url")

                val result = publisher.publish(events(3))

                result.succeededOutboxIds shouldBe setOf(1L)
                result.failedOutboxIds shouldBe setOf(2L, 3L)
            }
        }

        `when`("한 묶음 전송에서 예외가 나면") {
            then("그 묶음만 실패 처리하고 다음 묶음을 계속 보낸다") {
                val sqsClient = mock(SqsClient::class.java)
                `when`(sqsClient.sendMessageBatch(any(SendMessageBatchRequest::class.java)))
                    .thenThrow(IllegalStateException("SQS 장애"))
                    .thenReturn(success(listOf("11")))
                val publisher = SqsFoodContentEventPublisher(sqsClient, mapper, "queue-url")

                val result = publisher.publish(events(11))

                result.succeededOutboxIds shouldBe setOf(11L)
                result.failedOutboxIds shouldBe (1L..10L).toSet()
                verify(sqsClient, org.mockito.Mockito.times(2)).sendMessageBatch(any(SendMessageBatchRequest::class.java))
            }
        }
    }
})
