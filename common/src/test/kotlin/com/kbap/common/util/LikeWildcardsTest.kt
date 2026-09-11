package com.kbap.common.util

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class LikeWildcardsTest : BehaviorSpec({

    given("LikeWildcards.escape") {
        `when`("%·_·백슬래시가 든 키워드를 이스케이프하면") {
            then("각 와일드카드가 리터럴 매칭용으로 바뀐다") {
                LikeWildcards.escape("100%생과일") shouldBe """100\%생과일"""
                LikeWildcards.escape("소금_설탕") shouldBe """소금\_설탕"""
                LikeWildcards.escape("""백\슬래시""") shouldBe """백\\슬래시"""
            }
        }

        `when`("와일드카드가 없는 키워드를 이스케이프하면") {
            then("그대로 반환한다") {
                LikeWildcards.escape("김치찌개") shouldBe "김치찌개"
            }
        }
    }
})
