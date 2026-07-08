package com.meogo.core.member

import com.meogo.core.kernel.lang.LanguageCode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class MemberProfileTest : BehaviorSpec({

    fun profile(spiciness: Int?) = MemberProfile(
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

        `when`("맵기 선호가 null 이면") {
            then("미설정으로 허용된다") {
                MemberProfile.empty().spicinessPreference shouldBe null
            }
        }
    }

    given("MemberProfile 값 보존") {
        `when`("닉네임·기피성분·국가·언어를 담으면") {
            then("그대로 보존한다") {
                val profile = MemberProfile(
                    nickname = "머고",
                    avoidanceSubstanceCodes = setOf(AvoidanceSubstanceCodeRef("PEANUT"), AvoidanceSubstanceCodeRef("MILK")),
                    spicinessPreference = 3,
                    countryCode = "KR",
                    appLanguage = LanguageCode.JA,
                )

                profile.nickname shouldBe "머고"
                profile.avoidanceSubstanceCodes.map { it.value }.toSet() shouldBe setOf("PEANUT", "MILK")
                profile.countryCode shouldBe "KR"
                profile.appLanguage shouldBe LanguageCode.JA
            }
        }
    }
})
