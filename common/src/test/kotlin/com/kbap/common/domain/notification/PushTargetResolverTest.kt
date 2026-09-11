package com.kbap.common.domain.notification

import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.notification.model.DevicePlatform
import com.kbap.common.domain.notification.model.NotificationConsent
import com.kbap.common.domain.notification.model.NotificationConsentType
import com.kbap.common.domain.notification.model.NotificationConsents
import com.kbap.common.domain.notification.model.NotificationDevice
import com.kbap.common.domain.notification.model.NotificationSetting
import com.kbap.common.domain.notification.model.NotificationType
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.LocalDateTime

@SpringBootTest
@Import(MySqlContainerConfig::class)
class PushTargetResolverTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var resolver: PushTargetResolver

    @Autowired
    private lateinit var deviceRepository: NotificationDeviceJpaRepository

    @Autowired
    private lateinit var settingRepository: NotificationSettingJpaRepository

    @Autowired
    private lateinit var consentRepository: NotificationConsentJpaRepository

    init {
        val now = LocalDateTime.of(2026, 9, 11, 12, 0)
        var seq = 0

        fun clear() {
            deviceRepository.deleteAll()
            settingRepository.deleteAll()
            consentRepository.deleteAll()
        }

        fun device(memberId: Long, lang: String = "ko", invalid: Boolean = false): NotificationDevice {
            val id = "inst-${++seq}"
            val device = NotificationDevice.register(id, "ExponentPushToken[$id]", DevicePlatform.IOS, lang, memberId)
            if (invalid) device.markTokenInvalid(now)
            return deviceRepository.save(device)
        }

        fun setting(device: NotificationDevice, activity: Boolean = false, mealTime: Boolean = false, news: Boolean = false) {
            settingRepository.save(
                NotificationSetting(
                    memberId = device.memberId!!,
                    installationId = device.installationId,
                    activity = activity,
                    mealTime = mealTime,
                    news = news,
                ),
            )
        }

        fun legacySetting(memberId: Long, activity: Boolean, mealTime: Boolean) {
            settingRepository.save(NotificationSetting(memberId = memberId, activity = activity, mealTime = mealTime, news = true))
        }

        fun consent(memberId: Long, type: NotificationConsentType, version: Int) {
            consentRepository.save(NotificationConsent.grantForMember(memberId, null, type, version, now))
        }

        fun bothConsents(memberId: Long, version: Int) {
            consent(memberId, NotificationConsentType.MARKETING_PRIVACY, version)
            consent(memberId, NotificationConsentType.MARKETING_RECEIVE, version)
        }

        fun tokensOf(memberIds: Collection<Long>, type: NotificationType): List<String> =
            resolver.resolve(memberIds, type).map { it.expoToken }

        given("활동 알림(HELPFUL·REVIEW_REMINDER) 대상 필터") {
            `when`("같은 회원의 기기 A 는 켜짐·기기 B 는 꺼짐이고 설정 없는 기기가 섞여 있으면") {
                clear()
                val a = device(1L)
                val b = device(1L, lang = "ja")
                device(1L, lang = "en")
                device(2L)
                setting(a, activity = true)
                setting(b, activity = false)

                then("켜진 기기 A 만 돌려준다") {
                    tokensOf(listOf(1L, 2L), NotificationType.HELPFUL) shouldBe listOf(a.expoToken)
                    tokensOf(listOf(1L, 2L), NotificationType.REVIEW_REMINDER) shouldBe listOf(a.expoToken)
                }
            }

            `when`("회원 단위 구 계약 행(기기 식별자 없음)만 켜져 있으면") {
                clear()
                device(3L)
                legacySetting(3L, activity = true, mealTime = true)

                then("기기 행이 아니므로 대상이 아니다") {
                    tokensOf(listOf(3L), NotificationType.HELPFUL).shouldBeEmpty()
                }
            }
        }

        given("식사시간(MEAL_TIME) 대상 필터") {
            `when`("기기 meal_time 토글과 회원 광고성 동의 조합이 다양하면") {
                clear()
                val ok = device(10L)
                setting(ok, mealTime = true)
                bothConsents(10L, 2)

                setting(device(11L), activity = true, mealTime = true)

                setting(device(12L), mealTime = true)
                consent(12L, NotificationConsentType.MARKETING_RECEIVE, 2)

                setting(device(13L), mealTime = true)
                bothConsents(13L, 1)

                setting(device(14L), mealTime = false, news = true)
                bothConsents(14L, 2)

                then("기기 토글 on + 회원 두 동의 모두 v2 이상인 기기만 포함한다") {
                    tokensOf(listOf(10L, 11L, 12L, 13L, 14L), NotificationType.MEAL_TIME) shouldBe listOf(ok.expoToken)
                }
            }
        }

        given("스캔 제안(SCAN_SUGGESTION)·소식(NEWS) 대상 필터") {
            `when`("기기 news 토글과 회원 동의 조합이 다양하면") {
                clear()
                val ok = device(20L)
                setting(ok, news = true)
                bothConsents(20L, 2)

                val toggledOff = device(20L, lang = "ja")
                setting(toggledOff, news = false)

                setting(device(21L), news = true)

                setting(device(22L), news = true)
                bothConsents(22L, 1)

                device(23L)
                bothConsents(23L, 2)

                then("기기 news on + 회원 두 동의 v2 이상인 기기만 포함한다") {
                    tokensOf(listOf(20L, 21L, 22L, 23L), NotificationType.SCAN_SUGGESTION) shouldBe listOf(ok.expoToken)
                    tokensOf(listOf(20L, 21L, 22L, 23L), NotificationType.NEWS) shouldBe listOf(ok.expoToken)
                }
            }
        }

        given("설정 행이 없는 기기") {
            `when`("토큰만 등록된 기기에 모든 유형을 발송하면") {
                clear()
                device(30L)
                bothConsents(30L, 2)

                then("어떤 유형도 대상이 아니다") {
                    NotificationType.entries.forEach { type ->
                        tokensOf(listOf(30L), type).shouldBeEmpty()
                    }
                }
            }
        }

        given("무효 토큰") {
            `when`("token_invalid_at 이 찍힌 기기가 있으면") {
                clear()
                val ok = device(40L)
                val invalid = device(40L, invalid = true)
                setting(ok, activity = true)
                setting(invalid, activity = true)

                then("제외한다") {
                    tokensOf(listOf(40L), NotificationType.HELPFUL) shouldBe listOf(ok.expoToken)
                }
            }
        }

        given("여러 회원 동시 조회") {
            `when`("회원마다 켜진 기기가 하나씩 있으면") {
                clear()
                val a = device(50L)
                val b = device(51L)
                setting(a, activity = true)
                setting(b, activity = true)
                setting(device(51L, lang = "ja"), activity = false)

                then("회원별 켜진 기기를 모두 돌려준다") {
                    tokensOf(listOf(50L, 51L), NotificationType.HELPFUL) shouldContainExactlyInAnyOrder listOf(a.expoToken, b.expoToken)
                }
            }
        }

        given("빈 회원 목록") {
            `when`("resolve 하면") {
                then("빈 목록이다") {
                    resolver.resolve(emptyList(), NotificationType.HELPFUL).shouldBeEmpty()
                }
            }
        }

        given("광고성 동의 판정 함수") {
            fun open(type: NotificationConsentType, version: Int) =
                NotificationConsent.grantForMember(1L, null, type, version, now)

            `when`("두 종류 모두 요구 버전 이상이면") {
                then("true 다") {
                    NotificationConsents.isMarketingEnabled(
                        listOf(open(NotificationConsentType.MARKETING_PRIVACY, 2), open(NotificationConsentType.MARKETING_RECEIVE, 3)),
                        2,
                    ) shouldBe true
                }
            }

            `when`("한 종류만 있거나 한 종류가 구 버전이면") {
                then("false 다") {
                    NotificationConsents.isMarketingEnabled(listOf(open(NotificationConsentType.MARKETING_RECEIVE, 2)), 2) shouldBe false
                    NotificationConsents.isMarketingEnabled(
                        listOf(open(NotificationConsentType.MARKETING_PRIVACY, 1), open(NotificationConsentType.MARKETING_RECEIVE, 2)),
                        2,
                    ) shouldBe false
                }
            }

            `when`("요구 버전이 0 이면") {
                then("v1 동의도 통과한다") {
                    NotificationConsents.isMarketingEnabled(
                        listOf(open(NotificationConsentType.MARKETING_PRIVACY, 1), open(NotificationConsentType.MARKETING_RECEIVE, 1)),
                        0,
                    ) shouldBe true
                }
            }
        }
    }
}
