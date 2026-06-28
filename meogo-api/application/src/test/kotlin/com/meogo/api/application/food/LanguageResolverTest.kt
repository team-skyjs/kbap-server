package com.meogo.api.application.food

import com.meogo.api.food.LanguageCode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class LanguageResolverTest : BehaviorSpec({
    val resolver = LanguageResolver()

    given("LanguageResolver 언어 해석") {
        `when`("지원하는 언어 코드가 주어지면") {
            then("해당 LanguageCode 로 해석한다") {
                resolver.resolve("ko") shouldBe LanguageCode.KO
                resolver.resolve("en") shouldBe LanguageCode.EN
                resolver.resolve("ja") shouldBe LanguageCode.JA
                resolver.resolve("zh-Hans") shouldBe LanguageCode.ZH_HANS
            }
        }

        `when`("지원하지 않는 코드가 주어지면") {
            then("ko 로 폴백한다") {
                resolver.resolve("xx") shouldBe LanguageCode.KO
            }
        }

        `when`("미지정(null) 이거나 blank 이면") {
            then("ko 로 폴백한다") {
                resolver.resolve(null) shouldBe LanguageCode.KO
                resolver.resolve("") shouldBe LanguageCode.KO
                resolver.resolve("   ") shouldBe LanguageCode.KO
            }
        }
    }
})
