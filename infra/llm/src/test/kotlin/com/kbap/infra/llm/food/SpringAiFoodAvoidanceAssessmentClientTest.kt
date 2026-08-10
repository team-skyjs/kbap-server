package com.kbap.infra.llm.food

import com.kbap.common.port.llm.FoodAvoidanceAssessment
import com.kbap.infra.llm.client.LlmModelCaller
import com.kbap.infra.llm.model.LlmChatRequest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

private class AvoidanceCaller(val behavior: () -> String) : LlmModelCaller {
    override fun call(request: LlmChatRequest): String = behavior()
}

private fun assessmentJson(spiciness: Int?, vararg pairs: Pair<String, Int>): String {
    val items = pairs.joinToString(", ") { "{\"code\": \"${it.first}\", \"inclusionPercent\": ${it.second}}" }
    val spicinessField = spiciness?.let { ", \"spiciness\": $it" } ?: ""
    return """{"assessments": [$items]$spicinessField}"""
}

private fun clientOf(response: String) = SpringAiFoodAvoidanceAssessmentClient(AvoidanceCaller { response })

class SpringAiFoodAvoidanceAssessmentClientTest : BehaviorSpec({
    val candidates = setOf("PORK", "BEEF")

    given("모델이 성분과 맵기를 정상 응답") {
        val client = clientOf(assessmentJson(4, "PORK" to 80, "BEEF" to 0))

        `when`("기피성분 조사를 호출하면") {
            val result = client.call("제육볶음", candidates)
            then("포함률 0 인 성분은 빼고 맵기는 그대로 싣는다") {
                result.substances shouldContainExactlyInAnyOrder listOf(FoodAvoidanceAssessment("PORK", 80))
                result.spiciness shouldBe 4
            }
        }
    }

    given("모델이 후보 밖 코드를 섞어 응답") {
        val client = clientOf(assessmentJson(3, "PORK" to 90, "OIL" to 80))

        `when`("기피성분 조사를 호출하면") {
            val result = client.call("제육볶음", candidates)
            then("후보 밖 항목만 스킵하고 응답 전체는 유효로 인정한다") {
                result.substances shouldContainExactlyInAnyOrder listOf(FoodAvoidanceAssessment("PORK", 90))
                result.spiciness shouldBe 3
            }
        }
    }

    given("모델이 후보 밖 코드만 응답") {
        val client = clientOf(assessmentJson(2, "OIL" to 80))

        `when`("기피성분 조사를 호출하면") {
            val result = client.call("아메리카노", candidates)
            then("성분 없음과 맵기로 종합한다") {
                result.substances shouldBe emptyList()
                result.spiciness shouldBe 2
            }
        }
    }

    given("후보 밖 코드가 범위 밖 포함률로 중복 응답") {
        val client = clientOf(assessmentJson(3, "OIL" to 150, "OIL" to 40, "PORK" to 80))

        `when`("기피성분 조사를 호출하면") {
            val result = client.call("제육볶음", candidates)
            then("후보 밖 항목의 범위·중복 위반은 응답 무효를 촉발하지 않고 모두 스킵된다") {
                result.substances shouldContainExactlyInAnyOrder listOf(FoodAvoidanceAssessment("PORK", 80))
                result.spiciness shouldBe 3
            }
        }
    }

    given("후보 안 코드의 포함률이 범위 밖") {
        val client = clientOf(assessmentJson(3, "OIL" to 50, "PORK" to 150))

        `when`("기피성분 조사를 호출하면") {
            then("응답 전체가 무효라 종합 불가 예외를 던진다") {
                shouldThrow<IllegalArgumentException> { client.call("제육볶음", candidates) }
            }
        }
    }

    given("후보 안 코드가 중복") {
        val client = clientOf(assessmentJson(3, "PORK" to 100, "PORK" to 0))

        `when`("기피성분 조사를 호출하면") {
            then("응답 전체가 무효라 종합 불가 예외를 던진다") {
                shouldThrow<IllegalArgumentException> { client.call("제육볶음", candidates) }
            }
        }
    }

    given("맵기가 범위 밖(11)") {
        val client = clientOf(assessmentJson(11, "PORK" to 100))

        `when`("기피성분 조사를 호출하면") {
            then("성분까지 전체 무효 처리되어 예외를 던진다") {
                shouldThrow<IllegalArgumentException> { client.call("제육볶음", candidates) }
            }
        }
    }

    given("맵기를 누락한 응답") {
        val client = clientOf(assessmentJson(null, "PORK" to 100))

        `when`("기피성분 조사를 호출하면") {
            then("전체 무효 처리되어 예외를 던진다") {
                shouldThrow<IllegalArgumentException> { client.call("제육볶음", candidates) }
            }
        }
    }

    given("맵기가 명시적 null 인 응답") {
        val client = clientOf("""{"assessments": [{"code": "PORK", "inclusionPercent": 100}], "spiciness": null}""")

        `when`("기피성분 조사를 호출하면") {
            then("null 이 0 으로 강제 변환되지 않고 전체 무효 처리된다") {
                shouldThrow<IllegalArgumentException> { client.call("제육볶음", candidates) }
            }
        }
    }

    given("포함률이 명시적 null 인 응답") {
        val client = clientOf("""{"assessments": [{"code": "PORK", "inclusionPercent": null}], "spiciness": 5}""")

        `when`("기피성분 조사를 호출하면") {
            then("전체 무효 처리되어 예외를 던진다") {
                shouldThrow<IllegalArgumentException> { client.call("제육볶음", candidates) }
            }
        }
    }

    given("JSON 이 아닌 응답") {
        val client = clientOf("판단할 수 없습니다")

        `when`("기피성분 조사를 호출하면") {
            then("파싱 실패로 종합 불가 예외를 던진다") {
                shouldThrow<IllegalArgumentException> { client.call("제육볶음", candidates) }
            }
        }
    }

    given("후보 코드가 빈 요청") {
        val client = clientOf(assessmentJson(0))

        `when`("기피성분 조사를 호출하면") {
            then("호출 전에 거절한다") {
                shouldThrow<IllegalArgumentException> { client.call("제육볶음", emptySet()) }
            }
        }
    }
})
