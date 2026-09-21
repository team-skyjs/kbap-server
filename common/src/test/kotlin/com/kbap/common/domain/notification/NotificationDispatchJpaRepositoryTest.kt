package com.kbap.common.domain.notification

import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.notification.model.Notification
import com.kbap.common.domain.notification.model.NotificationDispatch
import com.kbap.common.domain.notification.model.NotificationDispatchStatus
import com.kbap.common.domain.notification.model.NotificationType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.domain.Limit
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDateTime

@SpringBootTest
@Import(MySqlContainerConfig::class)
class NotificationDispatchJpaRepositoryTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var dispatchRepository: NotificationDispatchJpaRepository

    @Autowired
    private lateinit var notificationRepository: NotificationJpaRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    init {
        fun clear() {
            dispatchRepository.deleteAll()
            notificationRepository.deleteAll()
        }

        fun notification() = notificationRepository.save(
            Notification.forMember(1L, NotificationType.SCAN_SUGGESTION, "t", "b", mapOf("type" to "SCAN_SUGGESTION")),
        )

        fun dispatch(notificationId: Long, token: String, deviceId: Long? = null, type: NotificationType? = NotificationType.SCAN_SUGGESTION) =
            NotificationDispatch.pending(notificationId, deviceId, token, type)

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
            `when`("상태·유형·접수 시각이 섞인 발송이 있으면") {
                clear()
                val saved = notification()
                val now = LocalDateTime.of(2026, 9, 15, 12, 0)
                fun sentAt(createdAt: LocalDateTime, type: NotificationType? = NotificationType.SCAN_SUGGESTION, sent: Boolean = true): Long {
                    val d = dispatchRepository.save(dispatch(saved.id, "ExponentPushToken[x]", type = type).apply { if (sent) markSent("t") })
                    jdbcTemplate.update("UPDATE notification_dispatch SET created_at = ? WHERE id = ?", createdAt, d.id)
                    return d.id
                }
                val atLowerBound = sentAt(now.minusHours(24))
                val due = sentAt(now.minusMinutes(20))
                val atUpperBound = sentAt(now.minusMinutes(15))
                sentAt(now.minusHours(25))
                sentAt(now.minusMinutes(14))
                sentAt(now.minusMinutes(20), type = NotificationType.HELPFUL)
                sentAt(now.minusMinutes(20), type = null)
                sentAt(now.minusMinutes(20), sent = false)
                val types = listOf(NotificationType.SCAN_SUGGESTION, NotificationType.NEWS)
                fun find(afterId: Long, limit: Int) =
                    dispatchRepository.findSentForReceiptCheck(types, now.minusHours(24), now.minusMinutes(15), afterId, Limit.of(limit)).map { it.id }

                then("대상 유형의 SENT 중 시간 창(경계 포함) 안의 발송만 id 오름차순으로 돌려준다") {
                    find(0L, 10) shouldContainExactly listOf(atLowerBound, due, atUpperBound)
                }
                then("커서보다 큰 id 만 limit 건까지 돌려준다") {
                    find(atLowerBound, 1) shouldContainExactly listOf(due)
                    find(atUpperBound, 10) shouldHaveSize 0
                }
            }
        }

        given("알림별 발송 시도 조회") {
            `when`("한 알림에 발송 기록이 둘, 다른 알림에 하나 있으면") {
                clear()
                val first = notification()
                val second = notification()
                dispatchRepository.save(dispatch(first.id, "ExponentPushToken[a]"))
                dispatchRepository.save(dispatch(first.id, "ExponentPushToken[a]"))
                dispatchRepository.save(dispatch(second.id, "ExponentPushToken[b]"))

                then("요청한 알림의 기록만 전부 돌려준다") {
                    dispatchRepository.findByNotificationIdIn(listOf(first.id)) shouldHaveSize 2
                }
            }
        }
    }
}
