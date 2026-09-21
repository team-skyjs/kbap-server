package com.kbap.batch.notification

import com.kbap.batch.BatchIntegrationTest
import com.kbap.batch.trigger.rest.BatchJobLaunchResult
import com.kbap.batch.trigger.rest.BatchJobLauncher
import com.kbap.common.domain.notification.NotificationConsentJpaRepository
import com.kbap.common.domain.notification.NotificationDeviceJpaRepository
import com.kbap.common.domain.notification.NotificationDispatchJpaRepository
import com.kbap.common.domain.notification.NotificationJpaRepository
import com.kbap.common.domain.notification.NotificationSettingJpaRepository
import com.kbap.common.domain.notification.PushRequest
import com.kbap.common.domain.notification.model.DevicePlatform
import com.kbap.common.domain.notification.model.MealSlot
import com.kbap.common.domain.notification.model.NotificationConsent
import com.kbap.common.domain.notification.model.NotificationConsentType
import com.kbap.common.domain.notification.model.NotificationDevice
import com.kbap.common.domain.notification.model.NotificationDispatchStatus.DELIVERED
import com.kbap.common.domain.notification.model.NotificationDispatchStatus.FAILED
import com.kbap.common.domain.notification.model.NotificationDispatchStatus.SENT
import com.kbap.common.domain.notification.model.NotificationSetting
import com.kbap.common.domain.notification.model.NotificationType
import com.kbap.common.port.push.PushHandler
import com.kbap.common.port.push.PushReceipt
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.batch.core.job.JobExecution
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.time.LocalDateTime

private const val MARKETING_JOB = PushReceiptSyncBatchConfig.MARKETING_JOB
private const val ACTIVITY_JOB = PushReceiptSyncBatchConfig.ACTIVITY_JOB

@BatchIntegrationTest
class PushReceiptSyncJobTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var launcher: BatchJobLauncher

    @Autowired
    private lateinit var handler: PushHandler

    @Autowired
    private lateinit var deviceRepository: NotificationDeviceJpaRepository

    @Autowired
    private lateinit var settingRepository: NotificationSettingJpaRepository

    @Autowired
    private lateinit var consentRepository: NotificationConsentJpaRepository

    @Autowired
    private lateinit var notificationRepository: NotificationJpaRepository

    @Autowired
    private lateinit var dispatchRepository: NotificationDispatchJpaRepository

    @Autowired
    private lateinit var fakePushSender: FakePushSender

    @Autowired
    private lateinit var fakeReceipts: FakePushReceiptClient

    @Autowired
    private lateinit var clock: MutableClock

    @Autowired
    private lateinit var meterRegistry: MeterRegistry

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private val ok = PushReceipt(ok = true)
    private val rateExceeded = PushReceipt(ok = false, errorCode = "MessageRateExceeded", message = "slow down")
    private var seq = 0

    private fun clear() {
        dispatchRepository.deleteAll()
        notificationRepository.deleteAll()
        deviceRepository.deleteAll()
        settingRepository.deleteAll()
        consentRepository.deleteAll()
        fakePushSender.reset()
        fakeReceipts.reset()
        clock.set(Instant.now())
    }

    private fun member(memberId: Long): NotificationDevice {
        val id = "receipt-${++seq}"
        val device = deviceRepository.save(NotificationDevice.register(id, "ExponentPushToken[$id]", DevicePlatform.ANDROID, "ko", memberId))
        settingRepository.save(NotificationSetting(memberId = memberId, installationId = id, news = true, activity = true))
        NotificationConsentType.entries.forEach {
            consentRepository.save(NotificationConsent.grantForMember(memberId, null, it, 2, LocalDateTime.now()))
        }
        return device
    }

    private fun sendScanSuggestion(vararg memberIds: Long) {
        handler.send(PushRequest(NotificationType.SCAN_SUGGESTION, memberIds.toList(), ttlSeconds = 10800, mealSlot = MealSlot.LUNCH))
    }

    private fun sendHelpful(memberId: Long) {
        handler.send(PushRequest(NotificationType.HELPFUL, listOf(memberId), args = mapOf("food" to "김치찌개")))
    }

    private fun ageSentDispatches(minutes: Long) {
        jdbcTemplate.update("UPDATE notification_dispatch SET created_at = created_at - INTERVAL ? MINUTE WHERE dispatch_status = 'SENT'", minutes)
    }

    private fun run(jobName: String): JobExecution {
        val started = launcher.launch(jobName).shouldBeInstanceOf<BatchJobLaunchResult.Started>()
        repeat(200) {
            val execution = launcher.getExecution(started.execution.id)!!
            if (!execution.isRunning) return execution
            Thread.sleep(100)
        }
        error("잡이 20초 안에 끝나지 않았습니다")
    }

    private fun statuses() = dispatchRepository.findAll().sortedBy { it.id }.map { it.dispatchStatus }

    private fun counter(result: String): Double =
        meterRegistry.counter(PushReceiptSyncWriter.METRIC, "type", "SCAN_SUGGESTION", "result", result).count()

    init {
        given("푸시 영수증 확인 잡") {
            `when`("접수 시각이 다른 발송들의 영수증이 ok 이면") {
                clear()
                member(1L)
                member(2L)
                member(3L)
                sendScanSuggestion(1L)
                ageSentDispatches(25 * 60)
                sendScanSuggestion(2L)
                ageSentDispatches(20)
                sendScanSuggestion(3L)
                fakeReceipts.receiptFor = { ok }

                val execution = run(MARKETING_JOB)

                then("접수 후 15분~24시간 사이의 발송만 조회해 DELIVERED 로 확정한다") {
                    execution.exitStatus.exitCode shouldBe "COMPLETED"
                    fakeReceipts.requested shouldHaveSize 1
                    statuses() shouldContainExactly listOf(SENT, DELIVERED, SENT)
                    notificationRepository.findAll() shouldHaveSize 3
                }
            }

            `when`("영수증이 DeviceNotRegistered 이면") {
                clear()
                val device = member(4L)
                sendScanSuggestion(4L)
                ageSentDispatches(20)
                fakeReceipts.receiptFor = { PushReceipt(ok = false, errorCode = "DeviceNotRegistered", message = "gone") }

                run(MARKETING_JOB)

                then("FAILED 로 닫고 기기 토큰을 무효 처리하며 알림함 행을 지우고 재전송하지 않는다") {
                    statuses() shouldContainExactly listOf(FAILED)
                    deviceRepository.findById(device.id).get().tokenInvalidAt.shouldNotBeNull()
                    notificationRepository.findAll().shouldBeEmpty()
                    fakePushSender.sent shouldHaveSize 1
                }
            }

            `when`("영수증이 아직 없다가 다음 회차에 나오면") {
                clear()
                member(5L)
                sendScanSuggestion(5L)
                ageSentDispatches(20)
                val before = counter("pending")
                run(MARKETING_JOB)
                val afterFirst = statuses()
                fakeReceipts.receiptFor = { ok }

                run(MARKETING_JOB)

                then("첫 회차는 SENT 로 두고 다음 회차에 확정한다") {
                    afterFirst shouldContainExactly listOf(SENT)
                    counter("pending") - before shouldBe 1.0
                    statuses() shouldContainExactly listOf(DELIVERED)
                }
            }

            `when`("영수증 조회가 실패하면") {
                clear()
                member(6L)
                sendScanSuggestion(6L)
                ageSentDispatches(20)
                fakeReceipts.failWith = IllegalStateException("expo down")

                val execution = run(MARKETING_JOB)

                then("발송은 SENT 로 남고 잡은 정상 종료한다") {
                    execution.exitStatus.exitCode shouldBe "COMPLETED"
                    statuses() shouldContainExactly listOf(SENT)
                }
            }

            `when`("확인 대상이 250건이면") {
                clear()
                val members = (2001L..2250L).toList()
                members.forEach { member(it) }
                sendScanSuggestion(*members.toLongArray())
                ageSentDispatches(20)
                fakeReceipts.receiptFor = { ok }
                val before = counter("delivered")

                val execution = run(MARKETING_JOB)

                then("묶음 단위로 끝까지 확인하고 전달 건수를 남긴다") {
                    execution.stepExecutions.single().readCount shouldBe 250L
                    statuses().all { it == DELIVERED } shouldBe true
                    counter("delivered") - before shouldBe 250.0
                }
            }
        }

        given("영수증 실패 재전송") {
            `when`("영수증이 계속 MessageRateExceeded 이면") {
                clear()
                clock.setSeoul(2026, 9, 15, 11, 0)
                val failing = member(10L)
                member(10L)
                sendScanSuggestion(10L)
                val failingTickets = mutableSetOf(dispatchRepository.findAll().first { it.notificationDeviceId == failing.id }.ticketId!!)
                fakeReceipts.receiptFor = { if (it in failingTickets) rateExceeded else ok }
                val before = counter("resent")
                clock.set(Instant.now())

                repeat(3) {
                    ageSentDispatches(20)
                    run(MARKETING_JOB)
                    dispatchRepository.findAll().filter { it.dispatchStatus == SENT }.forEach { failingTickets += it.ticketId!! }
                }
                ageSentDispatches(20)
                run(MARKETING_JOB)

                then("그 기기에만 두 번 재전송하고 세 번째 실패에서 알림함 행을 지운다") {
                    val resent = fakePushSender.sent.drop(2)
                    resent shouldHaveSize 2
                    resent.all { it.to == failing.expoToken && it.channelId == "news" && it.ttlSeconds != null } shouldBe true
                    val attempts = dispatchRepository.findAll().filter { it.notificationDeviceId == failing.id }
                    attempts shouldHaveSize 3
                    attempts.all { it.dispatchStatus == FAILED && it.error == "MessageRateExceeded" } shouldBe true
                    attempts.map { it.notificationId }.toSet() shouldHaveSize 1
                    notificationRepository.findAll() shouldHaveSize 1
                    counter("resent") - before shouldBe 2.0
                }
            }

            `when`("재전송한 발송의 영수증이 ok 이면") {
                clear()
                member(11L)
                sendScanSuggestion(11L)
                ageSentDispatches(20)
                fakeReceipts.receiptFor = { rateExceeded }
                run(MARKETING_JOB)
                ageSentDispatches(20)
                fakeReceipts.receiptFor = { ok }

                run(MARKETING_JOB)

                then("실패 기록과 전달 기록이 남고 알림함 행은 유지된다") {
                    statuses() shouldContainExactly listOf(FAILED, DELIVERED)
                    notificationRepository.findAll() shouldHaveSize 1
                }
            }

            `when`("재전송 중인 회원이 있는 슬롯에 발송 잡이 다시 돌면") {
                clear()
                clock.setSeoul(2026, 9, 15, 11, 0)
                member(12L)
                run("scanSuggestionLunchPushJob")
                jdbcTemplate.update("UPDATE notification SET created_at = ?", LocalDateTime.now())
                clock.set(Instant.now())
                ageSentDispatches(20)
                fakeReceipts.receiptFor = { rateExceeded }
                run(MARKETING_JOB)
                val sentBeforeRerun = fakePushSender.sent.size
                clock.set(Instant.now())

                run("scanSuggestionLunchPushJob")

                then("그 회원은 걸러져 새로 발송되지 않는다") {
                    sentBeforeRerun shouldBe 2
                    fakePushSender.sent shouldHaveSize 2
                    notificationRepository.findAll() shouldHaveSize 1
                }
            }

            `when`("재전송 요청이 접수 단계에서 실패하면") {
                clear()
                member(13L)
                sendScanSuggestion(13L)
                ageSentDispatches(20)
                fakeReceipts.receiptFor = { rateExceeded }
                fakePushSender.errorFor = { "InvalidCredentials" }

                run(MARKETING_JOB)

                then("새 발송 이력도 FAILED 로 닫고 알림함 행을 지운다") {
                    statuses() shouldContainExactly listOf(FAILED, FAILED)
                    notificationRepository.findAll().shouldBeEmpty()
                }
            }
        }

        given("광고성·활동 알림 잡 분리") {
            `when`("두 유형과 유형 없는 발송이 섞여 있으면") {
                clear()
                member(20L)
                sendScanSuggestion(20L)
                sendHelpful(20L)
                sendHelpful(20L)
                ageSentDispatches(20)
                val untyped = dispatchRepository.findAll().last().id
                jdbcTemplate.update("UPDATE notification_dispatch SET notification_type = NULL WHERE id = ?", untyped)
                fakeReceipts.receiptFor = { ok }

                run(MARKETING_JOB)
                val afterMarketing = statuses()
                run(ACTIVITY_JOB)

                then("각 잡은 자기 유형만 확정하고 유형 없는 발송은 건드리지 않는다") {
                    afterMarketing shouldContainExactly listOf(DELIVERED, SENT, SENT)
                    statuses() shouldContainExactly listOf(DELIVERED, DELIVERED, SENT)
                }
            }
        }
    }
}
