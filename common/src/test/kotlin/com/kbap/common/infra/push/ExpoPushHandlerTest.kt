package com.kbap.common.infra.push

import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.notification.NotificationConsentJpaRepository
import com.kbap.common.domain.notification.NotificationDeviceJpaRepository
import com.kbap.common.domain.notification.NotificationDispatchJpaRepository
import com.kbap.common.domain.notification.NotificationJpaRepository
import com.kbap.common.domain.notification.NotificationSettingJpaRepository
import com.kbap.common.domain.notification.PushDispatchResult
import com.kbap.common.domain.notification.PushDispatchService
import com.kbap.common.domain.notification.PushRequest
import com.kbap.common.domain.notification.model.DevicePlatform
import com.kbap.common.domain.notification.model.NotificationConsent
import com.kbap.common.domain.notification.model.NotificationConsentType
import com.kbap.common.domain.notification.model.NotificationDevice
import com.kbap.common.domain.notification.model.NotificationDispatchStatus
import com.kbap.common.domain.notification.model.NotificationSetting
import com.kbap.common.domain.notification.model.NotificationType
import com.kbap.common.port.push.PushMessage
import com.kbap.common.port.push.PushSender
import com.kbap.common.port.push.PushTicket
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.LocalDateTime

@SpringBootTest
@Import(MySqlContainerConfig::class)
class ExpoPushHandlerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var dispatchService: PushDispatchService

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

    private class RecordingSender : PushSender {
        val sent = mutableListOf<PushMessage>()
        var calls = 0
        var errorFor: (PushMessage) -> String? = { null }

        override fun send(messages: List<PushMessage>): List<PushTicket> {
            calls++
            sent += messages
            return messages.mapIndexed { i, m -> errorFor(m)?.let { PushTicket.error(it) } ?: PushTicket.ok("t$i") }
        }
    }

    init {
        var seq = 0

        fun clear() {
            dispatchRepository.deleteAll()
            notificationRepository.deleteAll()
            deviceRepository.deleteAll()
            settingRepository.deleteAll()
            consentRepository.deleteAll()
        }

        fun newsConsent(memberId: Long) {
            NotificationConsentType.entries.forEach {
                consentRepository.save(NotificationConsent.grantForMember(memberId, null, it, 2, LocalDateTime.now()))
            }
        }

        fun device(memberId: Long, lang: String): NotificationDevice {
            val id = "handler-${++seq}"
            return deviceRepository.save(
                NotificationDevice.register(id, "ExponentPushToken[$id]", DevicePlatform.ANDROID, lang, memberId),
            )
        }

        fun newsOn(memberId: Long) {
            deviceRepository.findByMemberId(memberId).forEach {
                settingRepository.save(NotificationSetting(memberId = memberId, installationId = it.installationId, news = true))
            }
        }

        val newsArgs = mapOf("title" to "K-Bap", "body" to "b")

        given("공용 발송 부품 ExpoPushHandler") {
            `when`("소식 켜진 기기 두 대를 가진 회원에게 NEWS 를 보내면") {
                clear()
                device(11L, "ko")
                device(11L, "en")
                newsConsent(11L)
                newsOn(11L)
                val sender = RecordingSender()
                val handler = ExpoPushHandler(dispatchService, sender)

                val result = handler.send(PushRequest(NotificationType.NEWS, listOf(11L), args = newsArgs))

                then("기기마다 발송되고 dispatch 가 SENT 로 기록된다") {
                    result shouldBe PushDispatchResult(sent = 2, failed = 0)
                    sender.sent shouldHaveSize 2
                    sender.sent.map { it.to }.toSet() shouldBe deviceRepository.findByMemberId(11L).map { it.expoToken }.toSet()
                    dispatchRepository.findAll().all { it.dispatchStatus == NotificationDispatchStatus.SENT } shouldBe true
                }
            }

            `when`("sender 가 한 건을 error 티켓으로 돌려주면") {
                clear()
                device(12L, "ko")
                device(12L, "ja")
                newsConsent(12L)
                newsOn(12L)
                val sender = RecordingSender()
                sender.errorFor = { if (it.to.endsWith("-4]")) "DeviceNotRegistered" else null }
                val handler = ExpoPushHandler(dispatchService, sender)

                val result = handler.send(PushRequest(NotificationType.NEWS, listOf(12L), args = newsArgs))

                then("그 건만 FAILED 로 남고 집계가 (1,1) 이다") {
                    result shouldBe PushDispatchResult(sent = 1, failed = 1)
                    dispatchRepository.findAll().map { it.dispatchStatus }.sorted() shouldBe
                        listOf(NotificationDispatchStatus.SENT, NotificationDispatchStatus.FAILED).sorted()
                }
            }

            `when`("대상 기기가 없으면") {
                clear()
                device(13L, "ko")
                val sender = RecordingSender()
                val handler = ExpoPushHandler(dispatchService, sender)

                val result = handler.send(PushRequest(NotificationType.NEWS, listOf(13L), args = newsArgs))

                then("sender 를 부르지 않고 (0,0) 을 돌려준다") {
                    result shouldBe PushDispatchResult(sent = 0, failed = 0)
                    sender.calls shouldBe 0
                    notificationRepository.findAll() shouldHaveSize 0
                }
            }
        }
    }
}
