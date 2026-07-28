package com.kbap.api.core.logging

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class QueryMaskingTest : BehaviorSpec({

    given("쿼리 마스킹") {
        `when`("마스킹 대상 목록이 비어 있으면") {
            then("쿼리를 원문 그대로 남긴다") {
                maskQuery("keyword=kimchi&size=10", emptySet()) shouldBe "keyword=kimchi&size=10"
            }
        }

        `when`("마스킹 대상 파라미터가 섞여 있으면") {
            then("그 파라미터의 값만 가린다") {
                maskQuery("keyword=kimchi&token=abc123&size=10", setOf("token")) shouldBe
                    "keyword=kimchi&token=***&size=10"
            }
        }

        `when`("마스킹 대상이 여러 개면") {
            then("각각의 값을 모두 가린다") {
                maskQuery("token=abc&secret=xyz", setOf("token", "secret")) shouldBe "token=***&secret=***"
            }
        }

        `when`("값 없는 파라미터가 들어오면") {
            then("그대로 남긴다") {
                maskQuery("flag&keyword=kimchi", setOf("token")) shouldBe "flag&keyword=kimchi"
            }
        }

        `when`("쿼리가 없으면") {
            then("null 을 돌려준다") {
                maskQuery(null, setOf("token")) shouldBe null
                maskQuery("", setOf("token")) shouldBe null
            }
        }
    }
})
