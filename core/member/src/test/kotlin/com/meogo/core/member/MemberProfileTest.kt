package com.meogo.core.member

import com.meogo.core.kernel.lang.CountryCode
import com.meogo.core.kernel.lang.LanguageCode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class MemberProfileTest : BehaviorSpec({

    fun profile(spiciness: Int) = MemberProfile.of(
        nickname = null,
        avoidanceSubstanceCodes = emptySet(),
        spicinessPreference = spiciness,
        countryCode = null,
        appLanguage = null,
    )

    given("MemberProfile 맵기 선호 범위") {
        `when`("0~10 경계값이면") {
            then("정상 생성된다") {
                profile(0).spicinessPreference shouldBe 0
                profile(10).spicinessPreference shouldBe 10
            }
        }

        `when`("범위를 벗어나면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> { profile(-1) }
                shouldThrow<IllegalArgumentException> { profile(11) }
            }
        }
    }

    given("MemberProfile.empty — 가입 직후 기본 프로필") {
        `when`("빈 프로필을 만들면") {
            then("맵기 선호는 기본값 5, 기피성분은 빈 셋이다") {
                MemberProfile.empty().spicinessPreference shouldBe 5
                MemberProfile.empty().avoidanceSubstanceCodes shouldBe emptySet()
            }
        }
    }

    given("MemberProfile 값 보존") {
        `when`("닉네임·기피성분·국가·언어를 담으면") {
            then("그대로 보존한다") {
                val profile = MemberProfile.of(
                    nickname = "머고",
                    avoidanceSubstanceCodes = setOf(AvoidanceSubstanceCodeRef("PEANUT"), AvoidanceSubstanceCodeRef("MILK")),
                    spicinessPreference = 3,
                    countryCode = CountryCode.KR,
                    appLanguage = LanguageCode.JA,
                )

                profile.nickname shouldBe "머고"
                profile.avoidanceSubstanceCodes.map { it.value }.toSet() shouldBe setOf("PEANUT", "MILK")
                profile.countryCode shouldBe CountryCode.KR
                profile.appLanguage shouldBe LanguageCode.JA
            }
        }
    }

    given("MemberProfile 부분 수정 — 불변(새 인스턴스 반환)") {
        `when`("닉네임을 변경하면") {
            then("새 인스턴스에만 반영되고 나머지 항목과 원본은 유지된다") {
                val origin = MemberProfile.empty()

                val updated = origin.changeNickname("머고")

                updated.nickname shouldBe "머고"
                updated.spicinessPreference shouldBe origin.spicinessPreference
                origin.nickname shouldBe null
            }
        }

        `when`("기피성분을 변경하면") {
            then("새 인스턴스에만 반영되고 원본은 유지된다") {
                val origin = MemberProfile.empty()

                val updated = origin.changeAvoidanceSubstances(setOf(AvoidanceSubstanceCodeRef("PEANUT")))

                updated.avoidanceSubstanceCodes shouldBe setOf(AvoidanceSubstanceCodeRef("PEANUT"))
                origin.avoidanceSubstanceCodes shouldBe emptySet()
            }
        }

        `when`("맵기 선호를 변경하면") {
            then("새 인스턴스에만 반영되고 원본은 유지된다") {
                val origin = MemberProfile.empty()

                val updated = origin.changeSpicinessPreference(9)

                updated.spicinessPreference shouldBe 9
                origin.spicinessPreference shouldBe 5
            }
        }

        `when`("허용 범위를 벗어난 맵기 선호로 변경하면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> { MemberProfile.empty().changeSpicinessPreference(11) }
            }
        }

        `when`("국가·앱 언어를 변경하면") {
            then("새 인스턴스에만 반영되고 원본은 유지된다") {
                val origin = MemberProfile.empty()

                val updated = origin.changeCountry(CountryCode.JP).changeAppLanguage(LanguageCode.JA)

                updated.countryCode shouldBe CountryCode.JP
                updated.appLanguage shouldBe LanguageCode.JA
                origin.countryCode shouldBe null
                origin.appLanguage shouldBe null
            }
        }

        `when`("여러 항목을 연달아 변경하면") {
            then("앞선 변경이 보존된 채 누적된다") {
                val updated = MemberProfile.empty()
                    .changeNickname("머고")
                    .changeSpicinessPreference(2)
                    .changeCountry(CountryCode.KR)

                updated.nickname shouldBe "머고"
                updated.spicinessPreference shouldBe 2
                updated.countryCode shouldBe CountryCode.KR
            }
        }
    }
})
