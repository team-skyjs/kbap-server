package com.kbap.common.domain.notification

import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.notification.model.Notification
import com.kbap.common.domain.notification.model.NotificationDispatch
import com.kbap.common.domain.notification.model.NotificationDispatchStatus
import com.kbap.common.domain.notification.model.NotificationType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageRequest
import java.time.LocalDateTime

@SpringBootTest
@Import(MySqlContainerConfig::class)
class NotificationDispatchJpaRepositoryTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var dispatchRepository: NotificationDispatchJpaRepository

    @Autowired
    private lateinit var notificationRepository: NotificationJpaRepository

    init {
        fun clear() {
            dispatchRepository.deleteAll()
            notificationRepository.deleteAll()
        }

        fun notification() = notificationRepository.save(
            Notification.forMember(1L, NotificationType.NUDGE, "t", "b", mapOf("type" to "NUDGE")),
        )

        fun dispatch(notificationId: Long, token: String, deviceId: Long? = null) =
            NotificationDispatch.pending(notificationId = notificationId, notificationDeviceId = deviceId, expoToken = token)

        given("알림 1건의 기기별 발송 추적") {
            `when`("기기 두 대로 발송 기록을 남기면") {
                clear()
                val saved = notification()
                dispatchRepository.save(dispatch(saved.id, "ExponentPushToken[a]", 10L))
                dispatchRepository.save(dispatch(saved.id, "ExponentPushToken[b]", 11L))

                then("알림 기준으로 2건이 토큰 스냅샷과 함께 조회된다") {
                    val found = dispatchRepository.findByNotificationId(saved.id)
                    found shouldHaveSize 2
                    found.map { it.expoToken } shouldContainExactlyInAnyOrder listOf("ExponentPushToken[a]", "ExponentPushToken[b]")
                    found.all { it.dispatchStatus == NotificationDispatchStatus.PENDING } shouldBe true
                    found.all { it.ticketId == null } shouldBe true
                }
            }
        }

        given("발송 상태 전이") {
            `when`("접수·배달 순서로 전이하면") {
                val d = dispatch(1L, "ExponentPushToken[a]")
                d.markSent("ticket-1")
                val afterSent = d.dispatchStatus
                d.markDelivered()

                then("PENDING → SENT → DELIVERED 로 옮겨 가고 접수 번호가 남는다") {
                    afterSent shouldBe NotificationDispatchStatus.SENT
                    d.dispatchStatus shouldBe NotificationDispatchStatus.DELIVERED
                    d.ticketId shouldBe "ticket-1"
                    d.error.shouldBeNull()
                }
            }

            `when`("접수된 발송이 기기 미등록으로 실패하면") {
                val d = dispatch(1L, "ExponentPushToken[a]")
                d.markSent("ticket-2")
                d.markFailed("DeviceNotRegistered")

                then("FAILED 와 오류 사유가 남는다") {
                    d.dispatchStatus shouldBe NotificationDispatchStatus.FAILED
                    d.error shouldBe "DeviceNotRegistered"
                }
            }

            `when`("접수 전에 배달 처리를 시도하면") {
                val d = dispatch(1L, "ExponentPushToken[a]")

                then("허용되지 않는 전이라 예외를 던진다") {
                    shouldThrow<IllegalStateException> { d.markDelivered() }
                }
            }
        }

        given("영수증 조회 대상") {
            `when`("접수 뒤 15분이 지난 발송과 방금 접수된 발송이 섞여 있으면") {
                clear()
                val saved = notification()
                val old = dispatchRepository.save(dispatch(saved.id, "ExponentPushToken[old]").apply { markSent("t-old") })
                dispatchRepository.save(dispatch(saved.id, "ExponentPushToken[new]").apply { markSent("t-new") })
                dispatchRepository.save(dispatch(saved.id, "ExponentPushToken[pending]"))
                val cutoff = LocalDateTime.now().plusSeconds(1)

                then("기준 시각 이전에 접수된 SENT 만 반환된다") {
                    val due = dispatchRepository.findByDispatchStatusAndCreatedAtBefore(
                        NotificationDispatchStatus.SENT,
                        cutoff,
                        PageRequest.of(0, 100),
                    )
                    due.map { it.id }.toSet() shouldBe setOf(old.id, due.first { it.expoToken == "ExponentPushToken[new]" }.id)
                    due.none { it.dispatchStatus == NotificationDispatchStatus.PENDING } shouldBe true
                    dispatchRepository.findByDispatchStatusAndCreatedAtBefore(
                        NotificationDispatchStatus.SENT,
                        cutoff.minusYears(1),
                        PageRequest.of(0, 100),
                    ) shouldHaveSize 0
                }
            }
        }
    }
}
