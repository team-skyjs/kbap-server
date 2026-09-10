package com.kbap.common.domain.notification

import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.notification.model.DevicePlatform
import com.kbap.common.domain.notification.model.NotificationDevice
import com.kbap.common.domain.notification.model.NotificationDispatchStatus
import com.kbap.common.domain.notification.model.NotificationSetting
import com.kbap.common.domain.notification.model.NotificationType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDateTime

@SpringBootTest
@Import(MySqlContainerConfig::class)
class PushDispatchServiceTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var service: PushDispatchService

    @Autowired
    private lateinit var renderer: PushMessageRenderer

    @Autowired
    private lateinit var deviceRepository: NotificationDeviceJpaRepository

    @Autowired
    private lateinit var settingRepository: NotificationSettingJpaRepository

    @Autowired
    private lateinit var notificationRepository: NotificationJpaRepository

    @Autowired
    private lateinit var dispatchRepository: NotificationDispatchJpaRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    init {
        var seq = 0

        fun clear() {
            dispatchRepository.deleteAll()
            notificationRepository.deleteAll()
            deviceRepository.deleteAll()
            settingRepository.deleteAll()
        }

        fun device(memberId: Long, lang: String, updatedAt: LocalDateTime): NotificationDevice {
            val id = "inst-${++seq}"
            val saved = deviceRepository.save(
                NotificationDevice.register(id, "ExponentPushToken[$id]", DevicePlatform.IOS, lang, memberId),
            )
            jdbcTemplate.update("UPDATE notification_device SET updated_at = ? WHERE id = ?", updatedAt, saved.id)
            return saved
        }

        fun activityOn(memberId: Long) {
            settingRepository.save(NotificationSetting(memberId = memberId, activity = true, mealTime = false))
        }

        val older = LocalDateTime.of(2026, 9, 1, 12, 0)
        val newer = LocalDateTime.of(2026, 9, 10, 12, 0)
        val helpfulArgs = mapOf("food" to "김치찌개")

        given("prepare") {
            `when`("회원 한 명이 ko(과거)·ja(최신) 기기 두 대를 쓰면") {
                clear()
                val ko = device(1L, "ko", older)
                val ja = device(1L, "ja", newer)
                activityOn(1L)

                val prepared = service.prepare(
                    PushRequest(NotificationType.HELPFUL, listOf(1L), args = helpfulArgs, data = mapOf("foodId" to "7")),
                )

                then("알림함 행 1개가 최신 기기 언어(ja)로 저장되고 data 에 type·foodId·notificationId 가 들어간다") {
                    val notifications = notificationRepository.findAll()
                    notifications shouldHaveSize 1
                    val n = notifications.single()
                    n.memberId shouldBe 1L
                    n.type shouldBe NotificationType.HELPFUL
                    n.title shouldBe renderer.render(NotificationType.HELPFUL, LanguageCode.JA, helpfulArgs, false).title
                    n.data!!["type"] shouldBe "HELPFUL"
                    n.data!!["foodId"] shouldBe "7"
                    (n.data!!["notificationId"] as Number).toLong() shouldBe n.id
                }

                then("기기마다 PENDING dispatch 와 기기 언어 봉투가 같은 순서로 만들어진다") {
                    val dispatches = dispatchRepository.findAll().sortedBy { it.id }
                    dispatches shouldHaveSize 2
                    dispatches.all { it.dispatchStatus == NotificationDispatchStatus.PENDING } shouldBe true
                    dispatches.map { it.notificationDeviceId }.toSet() shouldBe setOf(ko.id, ja.id)

                    prepared.messages shouldHaveSize 2
                    prepared.dispatchIds shouldBe dispatches.map { it.id }
                    val byToken = prepared.messages.associateBy { it.to }
                    byToken.getValue(ko.expoToken).title shouldBe
                        renderer.render(NotificationType.HELPFUL, LanguageCode.KO, helpfulArgs, false).title
                    byToken.getValue(ja.expoToken).title shouldBe
                        renderer.render(NotificationType.HELPFUL, LanguageCode.JA, helpfulArgs, false).title
                    prepared.messages.all { (it.data["notificationId"] as Number).toLong() == notificationRepository.findAll().single().id } shouldBe true
                    prepared.messages.zip(prepared.dispatchIds).all { (m, id) ->
                        dispatches.first { it.id == id }.expoToken == m.to
                    } shouldBe true
                }
            }

            `when`("대상 기기가 없으면") {
                clear()
                device(2L, "ko", newer)

                val prepared = service.prepare(PushRequest(NotificationType.HELPFUL, listOf(2L), args = helpfulArgs))

                then("아무것도 저장하지 않고 빈 결과를 돌려준다") {
                    prepared.isEmpty() shouldBe true
                    notificationRepository.findAll() shouldHaveSize 0
                    dispatchRepository.findAll() shouldHaveSize 0
                }
            }

            `when`("회원 두 명이 대상이면") {
                clear()
                device(3L, "ko", newer)
                device(4L, "en", newer)

                val prepared = service.prepare(
                    PushRequest(NotificationType.NOTICE, listOf(3L, 4L), args = mapOf("title" to "K-Bap", "body" to "b")),
                )

                then("알림함 행이 회원마다 하나씩 생긴다") {
                    notificationRepository.findAll().map { it.memberId }.toSet() shouldBe setOf(3L, 4L)
                    prepared.messages shouldHaveSize 2
                }
            }
        }

        given("record") {
            `when`("ok 와 error 결과를 반영하면") {
                clear()
                device(5L, "ko", newer)
                device(5L, "ko", older)
                val prepared = service.prepare(PushRequest(NotificationType.NOTICE, listOf(5L), args = mapOf("title" to "t", "body" to "b")))

                val result = service.record(
                    prepared,
                    listOf(PushOutcome(true, "t1", null), PushOutcome(false, null, "Boom")),
                )

                then("SENT+ticketId / FAILED+error 로 전이되고 집계를 돌려준다") {
                    result shouldBe PushDispatchResult(sent = 1, failed = 1)
                    val first = dispatchRepository.findById(prepared.dispatchIds[0]).get()
                    first.dispatchStatus shouldBe NotificationDispatchStatus.SENT
                    first.ticketId shouldBe "t1"
                    val second = dispatchRepository.findById(prepared.dispatchIds[1]).get()
                    second.dispatchStatus shouldBe NotificationDispatchStatus.FAILED
                    second.error!! shouldContain "Boom"
                }
            }

            `when`("DeviceNotRegistered 오류를 반영하면") {
                clear()
                val dead = device(6L, "ko", newer)
                val alive = device(6L, "ko", older)
                val prepared = service.prepare(PushRequest(NotificationType.NOTICE, listOf(6L), args = mapOf("title" to "t", "body" to "b")))
                val outcomes = prepared.messages.map { m ->
                    if (m.to == dead.expoToken) PushOutcome(false, null, "DeviceNotRegistered") else PushOutcome(true, "ok", null)
                }

                service.record(prepared, outcomes)

                then("그 기기만 토큰 무효 스탬프가 찍힌다") {
                    deviceRepository.findById(dead.id).get().tokenInvalidAt.shouldNotBeNull()
                    deviceRepository.findById(alive.id).get().tokenInvalidAt.shouldBeNull()
                }
            }

            `when`("결과 개수가 dispatch 개수와 다르면") {
                clear()
                device(7L, "ko", newer)
                val prepared = service.prepare(PushRequest(NotificationType.NOTICE, listOf(7L), args = mapOf("title" to "t", "body" to "b")))

                then("IllegalArgumentException 을 던진다") {
                    shouldThrow<IllegalArgumentException> { service.record(prepared, emptyList()) }
                }
            }
        }
    }
}
