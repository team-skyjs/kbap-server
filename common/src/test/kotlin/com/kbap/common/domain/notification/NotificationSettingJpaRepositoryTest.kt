package com.kbap.common.domain.notification

import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.notification.model.DevicePlatform
import com.kbap.common.domain.notification.model.NotificationDevice
import com.kbap.common.domain.notification.model.NotificationPreferences
import com.kbap.common.domain.notification.model.NotificationSetting
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import java.time.LocalDateTime

@SpringBootTest
@Import(MySqlContainerConfig::class)
class NotificationSettingJpaRepositoryTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var repository: NotificationSettingJpaRepository

    init {
        fun clear() = repository.deleteAll()
        val now = LocalDateTime.of(2026, 9, 7, 12, 0)

        given("설정 기록이 없는 회원") {
            `when`("회원 기준으로 조회하면") {
                clear()

                then("null 이고 기본 설정은 도움됨 on·리마인더 on·광고성 off 다") {
                    repository.findByMemberId(1L).shouldBeNull()
                    NotificationSetting.defaultFor(1L).preferences() shouldBe NotificationPreferences.DEFAULT
                }
            }

            `when`("같은 회원으로 두 번 저장하면") {
                clear()
                repository.save(NotificationSetting.defaultFor(1L))

                then("유니크 제약 위반 예외를 던진다") {
                    shouldThrow<DataIntegrityViolationException> {
                        repository.saveAndFlush(NotificationSetting.defaultFor(1L))
                    }
                }
            }
        }

        given("광고성 수신 동의 전환") {
            `when`("off 에서 문구 버전 v2 로 켜면") {
                clear()
                val setting = repository.save(NotificationSetting.defaultFor(2L))
                setting.updateMarketing(enabled = true, consentVersion = "v2", now = now)
                repository.saveAndFlush(setting)

                then("동의 시각이 서버 시각으로, 문구 버전이 v2 로 기록된다") {
                    val found = repository.findByMemberId(2L)!!
                    found.marketing shouldBe true
                    found.marketingConsentVersion shouldBe "v2"
                    found.marketingOptInAt shouldBe now
                }
            }

            `when`("같은 값으로 다시 켜면") {
                clear()
                val setting = repository.save(NotificationSetting.defaultFor(2L))
                setting.updateMarketing(enabled = true, consentVersion = "v2", now = now)
                setting.updateMarketing(enabled = true, consentVersion = "v2", now = now.plusHours(1))
                repository.saveAndFlush(setting)

                then("동의 시각은 처음 값 그대로다") {
                    repository.findByMemberId(2L)!!.marketingOptInAt shouldBe now
                }
            }

            `when`("켜진 상태에서 버전만 올리면") {
                clear()
                val setting = repository.save(NotificationSetting.defaultFor(2L))
                setting.updateMarketing(enabled = true, consentVersion = "v2", now = now)
                setting.updateMarketing(enabled = true, consentVersion = "v3", now = now.plusHours(1))
                repository.saveAndFlush(setting)

                then("버전과 시각이 다시 기록된다") {
                    val found = repository.findByMemberId(2L)!!
                    found.marketingConsentVersion shouldBe "v3"
                    found.marketingOptInAt shouldBe now.plusHours(1)
                }
            }

            `when`("켜진 동의를 끄면") {
                clear()
                val setting = repository.save(NotificationSetting.defaultFor(2L))
                setting.updateMarketing(enabled = true, consentVersion = "v2", now = now)
                setting.updateMarketing(enabled = false, consentVersion = null, now = now.plusDays(1))
                repository.saveAndFlush(setting)

                then("동의 시각과 문구 버전이 비워진다") {
                    val found = repository.findByMemberId(2L)!!
                    found.marketing shouldBe false
                    found.marketingConsentVersion.shouldBeNull()
                    found.marketingOptInAt.shouldBeNull()
                }
            }
        }

        given("광고성 발송 가능 판정") {
            `when`("요구 버전이 v2 일 때") {
                val on = NotificationSetting.defaultFor(3L).apply { updateMarketing(true, "v2", now) }
                val old = NotificationSetting.defaultFor(3L).apply { updateMarketing(true, "v1", now) }
                val off = NotificationSetting.defaultFor(3L)

                then("v2 동의만 참이고 구 문구·미동의는 거짓이다") {
                    on.isMarketingAllowed("v2") shouldBe true
                    old.isMarketingAllowed("v2") shouldBe false
                    off.isMarketingAllowed("v2") shouldBe false
                }
            }
        }

        given("게스트 기기 동의 승계") {
            `when`("동의한 게스트 기기로 회원 설정을 처음 만들면") {
                val device = NotificationDevice.register("inst-1", "ExponentPushToken[x]", DevicePlatform.ANDROID, "en")
                device.updateMarketing(enabled = true, consentVersion = "v2", now = now)
                val setting = NotificationSetting.inheritFrom(4L, device)

                then("기기의 동의·버전·시각이 그대로 복사되고 선호는 기본값이다") {
                    setting.memberId shouldBe 4L
                    setting.marketing shouldBe true
                    setting.marketingConsentVersion shouldBe "v2"
                    setting.marketingOptInAt shouldBe now
                    setting.helpful shouldBe true
                    setting.reviewReminder shouldBe true
                }
            }
        }

        given("선호 설정 변경") {
            `when`("도움됨과 리마인더를 끄면") {
                clear()
                val setting = repository.save(NotificationSetting.defaultFor(5L))
                setting.updateHelpful(false)
                setting.updateReviewReminder(false)
                repository.saveAndFlush(setting)

                then("두 선호가 off 로 저장된다") {
                    val found = repository.findByMemberId(5L)
                    found.shouldNotBeNull()
                    found.preferences() shouldBe NotificationPreferences(helpful = false, reviewReminder = false)
                }
            }
        }
    }
}
