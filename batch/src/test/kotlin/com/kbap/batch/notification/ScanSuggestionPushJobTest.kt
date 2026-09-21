package com.kbap.batch.notification

import com.kbap.batch.BatchIntegrationTest
import com.kbap.batch.trigger.rest.BatchJobLaunchResult
import com.kbap.batch.trigger.rest.BatchJobLauncher
import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.notification.NotificationConsentJpaRepository
import com.kbap.common.domain.notification.NotificationDeviceJpaRepository
import com.kbap.common.domain.notification.NotificationDispatchJpaRepository
import com.kbap.common.domain.notification.NotificationJpaRepository
import com.kbap.common.domain.notification.NotificationSettingJpaRepository
import com.kbap.common.domain.notification.PushTemplates
import com.kbap.common.domain.notification.model.DevicePlatform
import com.kbap.common.domain.notification.model.NotificationConsent
import com.kbap.common.domain.notification.model.Notification
import com.kbap.common.domain.notification.model.NotificationConsentType
import com.kbap.common.domain.notification.model.NotificationDevice
import com.kbap.common.domain.notification.model.NotificationDispatchStatus
import com.kbap.common.domain.notification.model.NotificationSetting
import com.kbap.common.domain.notification.model.NotificationType
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.batch.core.job.JobExecution
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.time.ZoneId
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.LocalDateTime

private const val JOB_NAME = "scanSuggestionLunchPushJob"
private const val DINNER_JOB_NAME = "scanSuggestionDinnerPushJob"

@BatchIntegrationTest
class ScanSuggestionPushJobTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var launcher: BatchJobLauncher

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
    private lateinit var clock: MutableClock

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var meterRegistry: MeterRegistry

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private fun byLangTitle(sent: List<com.kbap.common.port.push.PushMessage>, lang: String): String =
        sent.first { m -> deviceRepository.findAll().first { it.expoToken == m.to }.lang == lang }.title

    private fun stampCreatedAtToClock() {
        jdbcTemplate.update(
            "UPDATE notification SET created_at = ? WHERE created_at > ?",
            LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault()),
            LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault()),
        )
    }

    private fun sentCounter(): Double =
        meterRegistry.counter(ScanSuggestionPushWriter.METRIC, "type", "SCAN_SUGGESTION", "result", "sent").count()

    private var seq = 0

    private fun clear() {
        dispatchRepository.deleteAll()
        notificationRepository.deleteAll()
        deviceRepository.deleteAll()
        settingRepository.deleteAll()
        consentRepository.deleteAll()
        fakePushSender.reset()
    }

    private fun device(memberId: Long?, lang: String = "ko", tokenValid: Boolean = true): NotificationDevice {
        val id = "scan-${++seq}"
        val device = NotificationDevice.register(id, "ExponentPushToken[$id]", DevicePlatform.ANDROID, lang, memberId)
        if (!tokenValid) device.markTokenInvalid(LocalDateTime.now())
        return deviceRepository.save(device)
    }

    private fun consent(memberId: Long, version: Int = 2) {
        NotificationConsentType.entries.forEach {
            consentRepository.save(NotificationConsent.grantForMember(memberId, null, it, version, LocalDateTime.now()))
        }
    }

    private fun setting(memberId: Long, news: Boolean) {
        deviceRepository.findByMemberId(memberId).forEach {
            settingRepository.save(NotificationSetting(memberId = memberId, installationId = it.installationId, news = news))
        }
    }

    private fun run(jobName: String = JOB_NAME): JobExecution {
        val started = launcher.launch(jobName).shouldBeInstanceOf<BatchJobLaunchResult.Started>()
        repeat(200) {
            val execution = launcher.getExecution(started.execution.id)!!
            if (!execution.isRunning) return execution
            Thread.sleep(100)
        }
        error("잡이 20초 안에 끝나지 않았습니다")
    }

    init {
        given("스캔 제안 발송 잡") {
            `when`("점심 시각에 조건이 섞인 기기들을 두고 실행하면") {
                clear()
                clock.setSeoul(2026, 9, 15, 11, 0)
                device(1L, "ko")
                device(1L, "en")
                consent(1L)
                setting(1L, news = true)
                device(2L)
                consent(2L)
                setting(2L, news = false)
                device(3L)
                consent(3L)
                device(4L)
                consent(4L, version = 1)
                setting(4L, news = true)
                device(5L, tokenValid = false)
                consent(5L)
                setting(5L, news = true)
                device(null)

                val execution = run()

                then("소식 켜짐·동의 v2·유효 토큰·회원 연결 기기에만 알림함 행과 SENT 이력이 생긴다") {
                    execution.exitStatus.exitCode shouldBe "COMPLETED"
                    val notifications = notificationRepository.findAll()
                    notifications shouldHaveSize 2
                    notifications.all { it.memberId == 1L && it.type == NotificationType.SCAN_SUGGESTION } shouldBe true
                    notifications.forEach { n ->
                        n.data!!["type"] shouldBe "SCAN_SUGGESTION"
                        (n.data!!["notificationId"] as Number).toLong() shouldBe n.id
                    }
                    val dispatches = dispatchRepository.findAll()
                    dispatches shouldHaveSize 2
                    dispatches.all { it.dispatchStatus == NotificationDispatchStatus.SENT } shouldBe true
                }

                then("기기 언어로 렌더된 광고 표기 문구를 ttl 3시간으로 보낸다") {
                    val sent = fakePushSender.sent
                    sent shouldHaveSize 2
                    sent.forEach { it.title shouldStartWith "(광고) " }
                    byLangTitle(sent, "ko") shouldBe "(광고) 점심 먹을 때 스캔해보세요"
                    sent.forEach { it.ttlSeconds shouldBe 10800 }
                    sent.forEach { it.channelId shouldBe "news" }
                    val byLang = sent.associateBy { m -> deviceRepository.findAll().first { it.expoToken == m.to }.lang }
                    byLang.getValue("ko").body shouldEndWith PushTemplates.optOutNotice.getValue(LanguageCode.KO)
                    byLang.getValue("en").body shouldEndWith PushTemplates.optOutNotice.getValue(LanguageCode.EN)
                    byLang.getValue("ko").body shouldNotBe byLang.getValue("en").body
                }
            }

            `when`("같은 슬롯에서 다시 실행하면") {
                clear()
                clock.setSeoul(2026, 9, 15, 11, 0)
                val failing = device(8L, "ko")
                device(8L, "en")
                consent(8L)
                setting(8L, news = true)
                fakePushSender.errorFor = { if (it.to == failing.expoToken) "DeviceNotRegistered" else null }
                run()
                stampCreatedAtToClock()
                val afterFirst = fakePushSender.sent.size
                fakePushSender.errorFor = { null }

                val second = run()

                then("성공한 기기가 있는 회원은 다시 받지 않고, 실패한 건의 알림함 행은 사라진다") {
                    afterFirst shouldBe 2
                    second.exitStatus.exitCode shouldBe "COMPLETED"
                    fakePushSender.sent shouldHaveSize 2
                    notificationRepository.findAll() shouldHaveSize 1
                    dispatchRepository.findAll().count { it.dispatchStatus == NotificationDispatchStatus.FAILED } shouldBe 1
                }

                then("같은 날 저녁 슬롯에는 다시 보내고, 다음 날 점심 슬롯에도 다시 보낸다") {
                    clock.setSeoul(2026, 9, 15, 17, 0)
                    run()
                    stampCreatedAtToClock()
                    fakePushSender.sent shouldHaveSize 4
                    clock.setSeoul(2026, 9, 15, 20, 0)
                    run()
                    fakePushSender.sent shouldHaveSize 4
                    clock.setSeoul(2026, 9, 16, 11, 0)
                    run()
                    fakePushSender.sent shouldHaveSize 6
                    notificationRepository.findAll() shouldHaveSize 5
                }
            }

            `when`("HTTP 트리거로 실행하면") {
                clear()
                clock.setSeoul(2026, 9, 15, 11, 0)
                device(9L)
                consent(9L)
                setting(9L, news = true)
                val before = sentCounter()

                val body = mockMvc.post("/internal/batch/jobs?jobName=$JOB_NAME")
                    .andExpect { status { isAccepted() } }
                    .andReturn().response.contentAsString
                val executionId = com.jayway.jsonpath.JsonPath.read<Int>(body, "$.executionId").toLong()
                repeat(200) {
                    if (launcher.getExecution(executionId)?.isRunning == false) return@repeat
                    Thread.sleep(100)
                }

                then("202 로 받은 실행이 COMPLETED 로 조회되고 발송 카운터가 오른다") {
                    mockMvc.get("/internal/batch/executions/$executionId")
                        .andExpect {
                            status { isOk() }
                            jsonPath("$.jobName") { value(JOB_NAME) }
                            jsonPath("$.status") { value("COMPLETED") }
                        }
                    sentCounter() - before shouldBe 1.0
                }
            }

            `when`("회원 250명 중 40명이 이번 슬롯에 이미 받은 채로 실행하면") {
                clear()
                clock.setSeoul(2026, 9, 15, 11, 0)
                val members = (1001L..1250L).toList()
                val devices = deviceRepository.saveAll(
                    members.map { NotificationDevice.register("bulk-$it", "ExponentPushToken[bulk-$it]", DevicePlatform.ANDROID, "ko", it) },
                )
                settingRepository.saveAll(devices.map { NotificationSetting(memberId = it.memberId!!, installationId = it.installationId, news = true) })
                consentRepository.saveAll(
                    members.flatMap { m ->
                        NotificationConsentType.entries.map { NotificationConsent.grantForMember(m, null, it, 2, LocalDateTime.now()) }
                    },
                )
                notificationRepository.saveAll(
                    members.take(40).map { Notification(memberId = it, type = NotificationType.SCAN_SUGGESTION, title = "t", body = "b") },
                )
                val failing = setOf("ExponentPushToken[bulk-1100]", "ExponentPushToken[bulk-1250]")
                fakePushSender.errorFor = { if (it.to in failing) "DeviceNotRegistered" else null }

                val execution = run()

                then("스텝 하나가 이미 받은 회원을 뺀 대상을 100명씩 읽어 묶음 단위로 발송한다") {
                    execution.exitStatus.exitCode shouldBe "COMPLETED"
                    val step = execution.stepExecutions.single()
                    step.stepName shouldBe "scanSuggestionLunchSendStep"
                    step.readCount shouldBe 210L
                    step.writeCount shouldBe 210L
                    fakePushSender.batches shouldBe listOf(100, 100, 10)
                    dispatchRepository.findAll().count { it.dispatchStatus == NotificationDispatchStatus.FAILED } shouldBe 2
                    dispatchRepository.findAll().count { it.dispatchStatus == NotificationDispatchStatus.SENT } shouldBe 208
                }
            }

            `when`("저녁 잡을 실행하면") {
                clear()
                clock.setSeoul(2026, 9, 15, 17, 0)
                device(10L, "ko")
                consent(10L)
                setting(10L, news = true)

                val execution = run(DINNER_JOB_NAME)

                then("저녁 문구로 발송한다") {
                    execution.exitStatus.exitCode shouldBe "COMPLETED"
                    execution.jobInstance.jobName shouldBe DINNER_JOB_NAME
                    byLangTitle(fakePushSender.sent, "ko") shouldBe "(광고) 저녁 메뉴, 스캔해보세요"
                }
            }
        }
    }
}
