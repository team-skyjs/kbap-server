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

        fun setting(memberId: Long, activity: Boolean, mealTime: Boolean) {
            settingRepository.save(NotificationSetting(memberId = memberId, activity = activity, mealTime = mealTime))
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
            `when`("activity 토글이 켜진 회원·꺼진 회원·설정 없는 회원이 섞여 있으면") {
                clear()
                val a = device(1L).expoToken
                val a2 = device(1L, lang = "ja").expoToken
                device(2L)
                device(3L)
                setting(1L, activity = true, mealTime = false)
                setting(2L, activity = false, mealTime = true)

                then("켜진 회원의 기기 전부만 돌려준다") {
                    tokensOf(listOf(1L, 2L, 3L), NotificationType.HELPFUL) shouldContainExactlyInAnyOrder listOf(a, a2)
                    tokensOf(listOf(1L, 2L, 3L), NotificationType.REVIEW_REMINDER) shouldContainExactlyInAnyOrder listOf(a, a2)
                }
            }
        }

        given("식사시간(MEAL_TIME) 대상 필터") {
            `when`("meal_time 토글과 광고성 동의 조합이 다양하면") {
                clear()
                val ok = device(10L).expoToken
                setting(10L, activity = false, mealTime = true)
                bothConsents(10L, 2)

                device(11L)
                setting(11L, activity = true, mealTime = true)

                device(12L)
                setting(12L, activity = true, mealTime = true)
                consent(12L, NotificationConsentType.MARKETING_RECEIVE, 2)

                device(13L)
                setting(13L, activity = true, mealTime = true)
                bothConsents(13L, 1)

                device(14L)
                setting(14L, activity = true, mealTime = false)
                bothConsents(14L, 2)

                then("토글 on + 두 동의 모두 v2 이상인 회원만 포함한다") {
                    tokensOf(listOf(10L, 11L, 12L, 13L, 14L), NotificationType.MEAL_TIME) shouldBe listOf(ok)
                }
            }
        }

        given("스캔 제안(SCAN_SUGGESTION) 대상 필터") {
            `when`("토글은 꺼져 있고 동의만 있으면") {
                clear()
                val ok = device(20L).expoToken
                setting(20L, activity = false, mealTime = false)
                bothConsents(20L, 2)
                device(21L)
                setting(21L, activity = true, mealTime = true)

                then("동의 기준만으로 포함한다") {
                    tokensOf(listOf(20L, 21L), NotificationType.SCAN_SUGGESTION) shouldBe listOf(ok)
                }
            }
        }

        given("공지(NOTICE) 대상 필터") {
            `when`("토글·동의가 전혀 없으면") {
                clear()
                val ok = device(30L).expoToken

                then("유효 기기면 포함한다") {
                    tokensOf(listOf(30L), NotificationType.NOTICE) shouldBe listOf(ok)
                }
            }
        }

        given("무효 토큰") {
            `when`("token_invalid_at 이 찍힌 기기가 있으면") {
                clear()
                val ok = device(40L).expoToken
                device(40L, invalid = true)

                then("제외한다") {
                    tokensOf(listOf(40L), NotificationType.NOTICE) shouldBe listOf(ok)
                }
            }
        }

        given("빈 회원 목록") {
            `when`("resolve 하면") {
                then("빈 목록이다") {
                    resolver.resolve(emptyList(), NotificationType.NOTICE).shouldBeEmpty()
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
