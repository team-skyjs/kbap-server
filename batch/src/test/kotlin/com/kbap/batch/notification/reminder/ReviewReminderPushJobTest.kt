package com.kbap.batch.notification.reminder

import com.jayway.jsonpath.JsonPath
import com.kbap.batch.BatchIntegrationTest
import com.kbap.batch.notification.FakePushClient
import com.kbap.batch.notification.MutableClock
import com.kbap.batch.notification.PushDispatchMetric
import com.kbap.batch.notification.nowInJvmZone
import com.kbap.batch.trigger.rest.BatchJobLaunchResult
import com.kbap.batch.trigger.rest.BatchJobLauncher
import com.kbap.common.domain.notification.NotificationDeviceJpaRepository
import com.kbap.common.domain.notification.NotificationDispatchJpaRepository
import com.kbap.common.domain.notification.NotificationJpaRepository
import com.kbap.common.domain.notification.NotificationSettingJpaRepository
import com.kbap.common.domain.notification.model.DevicePlatform
import com.kbap.common.domain.notification.model.NotificationDevice
import com.kbap.common.domain.notification.model.NotificationDispatchStatus
import com.kbap.common.domain.notification.model.NotificationSetting
import com.kbap.common.domain.notification.model.NotificationType
import com.kbap.common.domain.order.OrderItemJpaRepository
import com.kbap.common.domain.order.OrderJpaRepository
import com.kbap.common.domain.order.model.Order
import com.kbap.common.domain.order.model.OrderItem
import com.kbap.common.domain.review.ReviewJpaRepository
import com.kbap.common.domain.review.model.Review
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldNotStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.batch.core.job.JobExecution
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.sql.Timestamp

@BatchIntegrationTest
class ReviewReminderPushJobTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var launcher: BatchJobLauncher

    @Autowired
    private lateinit var orderRepository: OrderJpaRepository

    @Autowired
    private lateinit var orderItemRepository: OrderItemJpaRepository

    @Autowired
    private lateinit var reviewRepository: ReviewJpaRepository

    @Autowired
    private lateinit var deviceRepository: NotificationDeviceJpaRepository

    @Autowired
    private lateinit var settingRepository: NotificationSettingJpaRepository

    @Autowired
    private lateinit var notificationRepository: NotificationJpaRepository

    @Autowired
    private lateinit var dispatchRepository: NotificationDispatchJpaRepository

    @Autowired
    private lateinit var fakePushClient: FakePushClient

    @Autowired
    private lateinit var clock: MutableClock

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var meterRegistry: MeterRegistry

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private var seq = 0

    private fun clear() {
        dispatchRepository.deleteAll()
        notificationRepository.deleteAll()
        deviceRepository.deleteAll()
        settingRepository.deleteAll()
        orderItemRepository.deleteAll()
        orderRepository.deleteAll()
        reviewRepository.deleteAll()
        fakePushClient.reset()
        clock.setSeoul(2026, 9, 20, 12, 0)
    }

    private fun member(memberId: Long, vararg langs: String, activity: Boolean = true) {
        langs.forEach { lang ->
            val id = "reminder-${++seq}"
            deviceRepository.save(NotificationDevice.register(id, "ExponentPushToken[$id]", DevicePlatform.ANDROID, lang, memberId))
            settingRepository.save(NotificationSetting(memberId = memberId, installationId = id, activity = activity))
        }
    }

    private fun order(memberId: Long, minutesAgo: Long, foodIds: List<Long> = listOf(1L, 2L)): Order {
        val order = orderRepository.save(Order.create(memberId, "job/${++seq}.jpg", null, null, null))
        foodIds.forEach { orderItemRepository.save(OrderItem.place(order.id, it, "menu-$it", 1, null)) }
        jdbcTemplate.update(
            "UPDATE orders SET created_at = ? WHERE id = ?",
            Timestamp.valueOf(clock.nowInJvmZone().minusMinutes(minutesAgo)),
            order.id,
        )
        return order
    }

    private fun stampNotificationsToClock() {
        val now = Timestamp.valueOf(clock.nowInJvmZone())
        jdbcTemplate.update("UPDATE notification SET created_at = ? WHERE created_at > ?", now, now)
    }

    private fun sentCounter(): Double =
        meterRegistry.counter(PushDispatchMetric.NAME, "type", "REVIEW_REMINDER", "result", "sent").count()

    private fun run(): JobExecution {
        val started = launcher.launch(ReviewReminderPushBatchConfig.JOB_NAME).shouldBeInstanceOf<BatchJobLaunchResult.Started>()
        repeat(200) {
            val execution = launcher.getExecution(started.execution.id)!!
            if (!execution.isRunning) return execution
            Thread.sleep(100)
        }
        error("잡이 20초 안에 끝나지 않았습니다")
    }

    private fun orderIdOf(data: Map<String, Any>): Long = (data.getValue("orderId") as Number).toLong()

    init {
        given("리뷰 리마인더 발송 잡") {
            `when`("70분 전 주문을 가진 회원의 기기가 두 대면") {
                clear()
                member(1L, "ko", "en")
                val order = order(1L, 70)

                val execution = run()

                then("기기마다 알림함 행과 SENT 이력이 생기고 data 는 type·orderId·notificationId 만 담는다") {
                    execution.exitStatus.exitCode shouldBe "COMPLETED"
                    val notifications = notificationRepository.findAll()
                    notifications shouldHaveSize 2
                    notifications.forEach { n ->
                        n.type shouldBe NotificationType.REVIEW_REMINDER
                        n.data!!["type"] shouldBe "REVIEW_REMINDER"
                        orderIdOf(n.data!!) shouldBe order.id
                        (n.data!!["notificationId"] as Number).toLong() shouldBe n.id
                        n.data!! shouldNotContainKey "foodId"
                        n.orderIdOrNull() shouldBe order.id
                    }
                    dispatchRepository.findAll().count { it.dispatchStatus == NotificationDispatchStatus.SENT } shouldBe 2
                }

                then("활동 채널로 광고 표기 없이 ttl 6시간·주문 맥락 문구를 보낸다") {
                    val sent = fakePushClient.sent
                    sent shouldHaveSize 2
                    sent.forEach {
                        it.channelId shouldBe "activity"
                        it.title shouldNotStartWith "(광고) "
                        it.ttlSeconds shouldBe 21600
                        it.body shouldNotContain "{"
                        orderIdOf(it.data) shouldBe order.id
                    }
                }
            }

            `when`("주문이 30분 전·26시간 전이면") {
                clear()
                member(2L, "ko")
                order(2L, 30)
                order(2L, 26 * 60)

                run()

                then("보내지 않는다") {
                    fakePushClient.sent shouldHaveSize 0
                    notificationRepository.findAll() shouldHaveSize 0
                }
            }

            `when`("발송한 뒤 5분 지나 다시 실행하면") {
                clear()
                member(3L, "ko")
                order(3L, 70)
                run()
                stampNotificationsToClock()
                clock.setSeoul(2026, 9, 20, 12, 5)

                run()

                then("같은 주문으로 다시 보내지 않는다") {
                    fakePushClient.sent shouldHaveSize 1
                    notificationRepository.findAll() shouldHaveSize 1
                }
            }

            `when`("같은 회원이 70분 전과 65분 전에 주문했으면") {
                clear()
                member(4L, "ko")
                val first = order(4L, 70)
                order(4L, 65)

                run()

                then("먼저 만든 주문으로 1건만 보낸다") {
                    fakePushClient.sent shouldHaveSize 1
                    orderIdOf(fakePushClient.sent.single().data) shouldBe first.id
                }
            }

            `when`("항목 리뷰가 전부 있는 주문과 일부만 있는 주문이 있으면") {
                clear()
                member(5L, "ko")
                order(5L, 70, listOf(1L, 2L))
                reviewRepository.save(Review(5L, 1L, 5))
                reviewRepository.save(Review(5L, 2L, 5))
                member(6L, "ko")
                val partly = order(6L, 70, listOf(1L, 2L))
                reviewRepository.save(Review(6L, 1L, 5))

                run()

                then("일부만 리뷰한 주문에만 보낸다") {
                    fakePushClient.sent shouldHaveSize 1
                    orderIdOf(fakePushClient.sent.single().data) shouldBe partly.id
                }
            }

            `when`("발송이 실패해 알림함 행이 사라진 뒤 다음 주기에 실행하면") {
                clear()
                member(7L, "ko")
                order(7L, 70)
                fakePushClient.errorFor = { "DeviceNotRegistered" }
                run()
                stampNotificationsToClock()
                fakePushClient.errorFor = { null }
                clock.setSeoul(2026, 9, 20, 12, 5)

                run()

                then("다시 대상이 되어 재발송한다") {
                    fakePushClient.sent shouldHaveSize 2
                    dispatchRepository.findAll().count { it.dispatchStatus == NotificationDispatchStatus.FAILED } shouldBe 1
                    dispatchRepository.findAll().count { it.dispatchStatus == NotificationDispatchStatus.SENT } shouldBe 1
                    notificationRepository.findAll() shouldHaveSize 1
                }
            }

            `when`("HTTP 트리거로 실행하면") {
                clear()
                member(8L, "ko")
                order(8L, 70)
                val before = sentCounter()

                val body = mockMvc.post("/internal/batch/jobs?jobName=${ReviewReminderPushBatchConfig.JOB_NAME}")
                    .andExpect { status { isAccepted() } }
                    .andReturn().response.contentAsString
                val executionId = JsonPath.read<Int>(body, "$.executionId").toLong()
                repeat(200) {
                    if (launcher.getExecution(executionId)?.isRunning == false) return@repeat
                    Thread.sleep(100)
                }

                then("202 로 받은 실행이 COMPLETED 로 조회되고 발송 카운터가 오른다") {
                    mockMvc.get("/internal/batch/executions/$executionId")
                        .andExpect {
                            status { isOk() }
                            jsonPath("$.jobName") { value(ReviewReminderPushBatchConfig.JOB_NAME) }
                            jsonPath("$.status") { value("COMPLETED") }
                        }
                    sentCounter() - before shouldBe 1.0
                }
            }
        }
    }
}
