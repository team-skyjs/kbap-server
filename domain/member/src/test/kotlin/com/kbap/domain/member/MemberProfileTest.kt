package com.kbap.domain.member

import com.kbap.core.lang.CountryCode
import com.kbap.core.lang.LanguageCode
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

    given("프로필 수정 — 통째 교체") {
        `when`("회원의 프로필을 새 프로필로 교체하면") {
            then("회원이 새 프로필을 갖고 프로필 값 객체 자체는 그대로다") {
                val origin = MemberProfile.empty()
                val member = Member.signUp(SocialIdentity(SocialProvider.GOOGLE, "sub-1", null))
                val replacement = MemberProfile.of(
                    nickname = "머고",
                    avoidanceSubstanceCodes = setOf(AvoidanceSubstanceCodeRef("PEANUT")),
                    spicinessPreference = 2,
                    countryCode = CountryCode.KR,
                    appLanguage = LanguageCode.KO,
                )

                member.updateProfile(replacement)

                member.profile shouldBe replacement
                origin.nickname shouldBe null
                origin.spicinessPreference shouldBe 5
            }
        }
    }
})
