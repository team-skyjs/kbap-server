package com.kbap.batch.notification

import com.kbap.batch.BatchIntegrationTest
import com.kbap.batch.trigger.BatchJobLaunchResult
import com.kbap.batch.trigger.BatchJobLauncher
import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.notification.NotificationConsentJpaRepository
import com.kbap.common.domain.notification.NotificationDeviceJpaRepository
import com.kbap.common.domain.notification.NotificationDispatchJpaRepository
import com.kbap.common.domain.notification.NotificationJpaRepository
import com.kbap.common.domain.notification.NotificationSettingJpaRepository
import com.kbap.common.domain.notification.PushTemplates
import com.kbap.common.domain.notification.model.DevicePlatform
import com.kbap.common.domain.notification.model.NotificationConsent
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
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDateTime

private const val JOB_NAME = "scanSuggestionPushJob"

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

    private fun run(): JobExecution {
        val started = launcher.launch(JOB_NAME).shouldBeInstanceOf<BatchJobLaunchResult.Started>()
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
                clock.setSeoul(2026, 9, 15, 12, 0)
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
                    sent.forEach { it.ttlSeconds shouldBe 10800 }
                    val byLang = sent.associateBy { m -> deviceRepository.findAll().first { it.expoToken == m.to }.lang }
                    byLang.getValue("ko").body shouldEndWith PushTemplates.optOutNotice.getValue(LanguageCode.KO)
                    byLang.getValue("en").body shouldEndWith PushTemplates.optOutNotice.getValue(LanguageCode.EN)
                    byLang.getValue("ko").body shouldNotBe byLang.getValue("en").body
                }
            }
        }
    }
}
