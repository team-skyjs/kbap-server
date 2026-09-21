package com.kbap.common.domain.notification

import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.notification.model.DevicePlatform
import com.kbap.common.domain.notification.model.NotificationConsent
import com.kbap.common.domain.notification.model.NotificationConsentType
import com.kbap.common.domain.notification.model.NotificationDevice
import com.kbap.common.domain.notification.model.NotificationDispatch
import com.kbap.common.domain.notification.model.NotificationDispatchStatus
import com.kbap.common.domain.notification.model.NotificationSetting
import com.kbap.common.domain.notification.model.NotificationType
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Duration
import java.time.LocalDateTime

@SpringBootTest
@Import(MySqlContainerConfig::class)
class PushReceiptServiceTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var service: PushReceiptService

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

    private val policy = ResendPolicy(maxResends = 2, window = Duration.ofHours(2))
    private val rateExceeded = ReceiptOutcome(ok = false, errorCode = "MessageRateExceeded", message = "slow down")
    private var seq = 0

    private fun clear() {
        dispatchRepository.deleteAll()
        notificationRepository.deleteAll()
        deviceRepository.deleteAll()
        settingRepository.deleteAll()
        consentRepository.deleteAll()
    }

    private fun device(memberId: Long): NotificationDevice {
        val id = "receipt-${++seq}"
        val device = deviceRepository.save(NotificationDevice.register(id, "ExponentPushToken[$id]", DevicePlatform.IOS, "ko", memberId))
        settingRepository.save(NotificationSetting(memberId = memberId, installationId = id, news = true, activity = true))
        return device
    }

    private fun consent(memberId: Long) {
        NotificationConsentType.entries.forEach {
            consentRepository.save(NotificationConsent.grantForMember(memberId, null, it, 2, LocalDateTime.now()))
        }
    }

    private fun accept(prepared: PreparedPush) {
        dispatchService.record(prepared, prepared.dispatchIds.map { PushOutcome(ok = true, ticketId = "ticket-$it", error = null) })
    }

    private fun send(type: NotificationType, memberId: Long): List<NotificationDispatch> {
        val args = if (type == NotificationType.NEWS) mapOf("title" to "새 소식", "body" to "본문") else mapOf("food" to "김치찌개")
        val prepared = dispatchService.prepare(PushRequest(type, listOf(memberId), args = args))
        accept(prepared)
        return dispatchRepository.findAllById(prepared.dispatchIds)
    }

    private fun apply(dispatch: NotificationDispatch, outcome: ReceiptOutcome, now: LocalDateTime = LocalDateTime.now()) =
        service.apply(mapOf(dispatch.id to outcome), policy, now)

    private fun reload(dispatch: NotificationDispatch) = dispatchRepository.findById(dispatch.id).get()

    init {
        given("영수증 결과 확정") {
            `when`("영수증이 ok 이면") {
                clear()
                val device = device(1L)
                consent(1L)
                val dispatch = send(NotificationType.NEWS, 1L).single()

                val result = apply(dispatch, ReceiptOutcome(ok = true))

                then("DELIVERED 가 되고 알림함 행은 남으며 재전송은 없다") {
                    reload(dispatch).dispatchStatus shouldBe NotificationDispatchStatus.DELIVERED
                    notificationRepository.findAll() shouldHaveSize 1
                    result.resend.isEmpty() shouldBe true
                    result.results shouldContainExactly listOf(NotificationType.NEWS to ReceiptResult.DELIVERED)
                    deviceRepository.findById(device.id).get().tokenInvalidAt.shouldBeNull()
                }
            }

            `when`("영수증이 DeviceNotRegistered 이면") {
                clear()
                val device = device(2L)
                consent(2L)
                val dispatch = send(NotificationType.NEWS, 2L).single()

                val result = apply(dispatch, ReceiptOutcome(ok = false, errorCode = "DeviceNotRegistered", message = "gone"))

                then("FAILED 로 닫고 기기 토큰을 무효 처리하며 알림함 행을 지우고 재전송하지 않는다") {
                    reload(dispatch).dispatchStatus shouldBe NotificationDispatchStatus.FAILED
                    reload(dispatch).error shouldBe "DeviceNotRegistered"
                    deviceRepository.findById(device.id).get().tokenInvalidAt.shouldNotBeNull()
                    notificationRepository.findAll().shouldBeEmpty()
                    result.resend.isEmpty() shouldBe true
                    result.results shouldContainExactly listOf(NotificationType.NEWS to ReceiptResult.FAILED)
                }
            }

            `when`("DeviceNotRegistered 영수증이 오기 전에 기기가 토큰을 재등록했으면") {
                clear()
                val device = device(5L)
                consent(5L)
                val dispatch = send(NotificationType.NEWS, 5L).single()
                deviceRepository.save(device.apply { expoToken = "ExponentPushToken[renewed]" })

                apply(dispatch, ReceiptOutcome(ok = false, errorCode = "DeviceNotRegistered", message = "gone"))

                then("옛 토큰의 오류로 새 토큰을 무효 처리하지 않는다") {
                    reload(dispatch).dispatchStatus shouldBe NotificationDispatchStatus.FAILED
                    deviceRepository.findById(device.id).get().tokenInvalidAt.shouldBeNull()
                }
            }

            `when`("다시 보내도 같은 결과인 오류이거나 모르는 오류 코드이면") {
                clear()
                device(3L)
                consent(3L)

                then("재전송 없이 FAILED 로 닫고 알림함 행을 지운다") {
                    listOf("MessageTooBig", "MismatchSenderId", "InvalidCredentials", "SomethingNew").forEach { code ->
                        val dispatch = send(NotificationType.NEWS, 3L).single()
                        val result = apply(dispatch, ReceiptOutcome(ok = false, errorCode = code, message = "m"))
                        reload(dispatch).error shouldBe code
                        result.resend.isEmpty() shouldBe true
                    }
                    notificationRepository.findAll().shouldBeEmpty()
                }
            }

            `when`("영수증이 아직 없는 발송이면") {
                clear()
                device(4L)
                consent(4L)
                val dispatch = send(NotificationType.NEWS, 4L).single()

                val result = service.apply(emptyMap(), policy, LocalDateTime.now())

                then("아무것도 바꾸지 않는다") {
                    reload(dispatch).dispatchStatus shouldBe NotificationDispatchStatus.SENT
                    result.results.shouldBeEmpty()
                }
            }
        }

        given("재전송") {
            `when`("기기 두 대 중 한 대의 영수증이 MessageRateExceeded 이면") {
                clear()
                val failing = device(10L)
                device(10L)
                consent(10L)
                val dispatches = send(NotificationType.NEWS, 10L)
                val dispatch = dispatches.first { it.notificationDeviceId == failing.id }

                val result = apply(dispatch, rateExceeded)

                then("그 발송을 FAILED 로 닫고 같은 알림에 새 발송 이력을 붙여 그 기기에만 다시 보낸다") {
                    reload(dispatch).dispatchStatus shouldBe NotificationDispatchStatus.FAILED
                    reload(dispatch).error shouldBe "MessageRateExceeded"
                    val message = result.resend.messages.single()
                    message.to shouldBe failing.expoToken
                    val notification = notificationRepository.findById(dispatch.notificationId).get()
                    message.title shouldBe notification.title
                    message.body shouldBe notification.body
                    message.data shouldBe notification.data
                    message.channelId shouldBe "news"
                    message.ttlSeconds!! shouldBeInRange 7100..7200
                    val retry = dispatchRepository.findById(result.resend.dispatchIds.single()).get()
                    retry.notificationId shouldBe dispatch.notificationId
                    retry.notificationType shouldBe NotificationType.NEWS
                    retry.dispatchStatus shouldBe NotificationDispatchStatus.PENDING
                    notificationRepository.findAll() shouldHaveSize 2
                    result.results shouldContainExactly listOf(NotificationType.NEWS to ReceiptResult.RESENT)
                }
            }

            `when`("세부 코드 없는 오류가 활동 알림에 오면") {
                clear()
                device(11L)
                val dispatch = send(NotificationType.HELPFUL, 11L).single()

                val result = apply(dispatch, ReceiptOutcome(ok = false, errorCode = null, message = "provider failed"))

                then("재전송하되 만료 시간은 두지 않는다") {
                    reload(dispatch).error shouldBe "provider failed"
                    result.resend.messages.single().ttlSeconds.shouldBeNull()
                    result.resend.messages.single().channelId shouldBe "activity"
                }
            }

            `when`("재전송한 발송이 연달아 MessageRateExceeded 이면") {
                clear()
                device(12L)
                consent(12L)
                val first = send(NotificationType.NEWS, 12L).single()

                val firstResult = apply(first, rateExceeded)
                accept(firstResult.resend)
                val secondResult = apply(dispatchRepository.findById(firstResult.resend.dispatchIds.single()).get(), rateExceeded)
                accept(secondResult.resend)
                val thirdResult = apply(dispatchRepository.findById(secondResult.resend.dispatchIds.single()).get(), rateExceeded)

                then("두 번까지만 재전송하고 세 번째 실패는 알림함 행을 지우며 시도마다 실패 기록이 남는다") {
                    thirdResult.resend.isEmpty() shouldBe true
                    thirdResult.results shouldContainExactly listOf(NotificationType.NEWS to ReceiptResult.FAILED)
                    val attempts = dispatchRepository.findByNotificationIdIn(listOf(first.notificationId))
                    attempts shouldHaveSize 3
                    attempts.all { it.dispatchStatus == NotificationDispatchStatus.FAILED && it.error == "MessageRateExceeded" } shouldBe true
                    notificationRepository.findAll().shouldBeEmpty()
                }
            }

            `when`("최초 발송 후 2시간이 지난 뒤면") {
                clear()
                device(13L)
                consent(13L)
                val dispatch = send(NotificationType.NEWS, 13L).single()

                val result = apply(dispatch, rateExceeded, now = LocalDateTime.now().plusHours(2).plusMinutes(1))

                then("횟수가 남았어도 재전송하지 않는다") {
                    result.resend.isEmpty() shouldBe true
                    notificationRepository.findAll().shouldBeEmpty()
                }
            }

            `when`("재전송 시점에 그 기기가 더는 발송 대상이 아니면") {
                clear()
                val device = device(14L)
                consent(14L)
                val dispatch = send(NotificationType.NEWS, 14L).single()
                settingRepository.findByMemberId(14L).forEach { settingRepository.save(it.apply { updateNews(false) }) }

                val result = apply(dispatch, rateExceeded)

                then("재전송하지 않고 최종 실패로 닫는다") {
                    result.resend.isEmpty() shouldBe true
                    notificationRepository.findAll().shouldBeEmpty()
                    deviceRepository.findById(device.id).get().tokenInvalidAt.shouldBeNull()
                }
            }

            `when`("그 사이 기기 토큰이 바뀌었으면") {
                clear()
                val device = device(15L)
                consent(15L)
                val dispatch = send(NotificationType.NEWS, 15L).single()
                deviceRepository.save(device.apply { expoToken = "ExponentPushToken[renewed]" })

                val result = apply(dispatch, rateExceeded)

                then("새 토큰으로 재전송한다") {
                    result.resend.messages.single().to shouldBe "ExponentPushToken[renewed]"
                }
            }
        }
    }
}
