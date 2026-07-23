package com.kbap.infra.llm.food

import com.kbap.core.food.FoodAvoidanceAssessment
import com.kbap.infra.llm.client.LlmFanoutClient
import com.kbap.infra.llm.client.LlmModelCaller
import com.kbap.infra.llm.model.LlmChatRequest
import com.kbap.infra.llm.model.LlmModelId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import java.util.concurrent.Executors

private class AvoidanceCaller(
    override val modelId: LlmModelId,
    val behavior: () -> String,
) : LlmModelCaller {
    override fun call(request: LlmChatRequest): String = behavior()
}

private fun assessmentJson(spiciness: Int?, vararg pairs: Pair<String, Int>): String {
    val items = pairs.joinToString(", ") { "{\"code\": \"${it.first}\", \"inclusionPercent\": ${it.second}}" }
    val spicinessField = spiciness?.let { ", \"spiciness\": $it" } ?: ""
    return """{"assessments": [$items]$spicinessField}"""
}

private fun fanoutOf(vararg callers: LlmModelCaller): LlmFanoutClient =
    LlmFanoutClient(callers.toList(), Executors.newVirtualThreadPerTaskExecutor())

class SpringAiFoodAvoidanceAssessmentClientTest : BehaviorSpec({
    val candidates = setOf("PORK", "BEEF")

    given("3개 모델이 모두 정상 응답(성분+맵기)") {
        val fanout = fanoutOf(
            AvoidanceCaller(LlmModelId.OPENAI) { assessmentJson(3, "PORK" to 80, "BEEF" to 0) },
            AvoidanceCaller(LlmModelId.UPSTAGE) { assessmentJson(4, "PORK" to 90, "BEEF" to 0) },
            AvoidanceCaller(LlmModelId.GEMINI) { assessmentJson(5, "PORK" to 70, "BEEF" to 0) },
        )
        val client = SpringAiFoodAvoidanceAssessmentClient(fanout)

        `when`("기피성분 조사를 호출하면") {
            val result = client.call("제육볶음", candidates)
            then("성분은 코드별 평균(0 제외), 맵기는 평균 반올림으로 함께 종합한다") {
                result.substances shouldContainExactlyInAnyOrder listOf(FoodAvoidanceAssessment("PORK", 80))
                result.spiciness shouldBe 4
            }
        }
    }

    given("한 모델이 후보 밖 코드를 섞어 응답") {
        val fanout = fanoutOf(
            AvoidanceCaller(LlmModelId.OPENAI) { assessmentJson(3, "PORK" to 80) },
            AvoidanceCaller(LlmModelId.UPSTAGE) { assessmentJson(4, "PORK" to 90) },
            AvoidanceCaller(LlmModelId.GEMINI) { assessmentJson(5, "CHICKEN" to 100) },
        )
        val client = SpringAiFoodAvoidanceAssessmentClient(fanout)

        `when`("기피성분 조사를 호출하면") {
            val result = client.call("제육볶음", candidates)
            then("위반 모델은 강등하고 유효한 2개로 성분·맵기를 종합한다") {
                result.substances shouldContainExactlyInAnyOrder listOf(FoodAvoidanceAssessment("PORK", 85))
                result.spiciness shouldBe 4
            }
        }
    }

    given("한 모델이 범위 밖 맵기(11)를 응답") {
        val fanout = fanoutOf(
            AvoidanceCaller(LlmModelId.OPENAI) { assessmentJson(3, "PORK" to 80) },
            AvoidanceCaller(LlmModelId.UPSTAGE) { assessmentJson(4, "PORK" to 90) },
            AvoidanceCaller(LlmModelId.GEMINI) { assessmentJson(11, "PORK" to 100) },
        )
        val client = SpringAiFoodAvoidanceAssessmentClient(fanout)

        `when`("기피성분 조사를 호출하면") {
            val result = client.call("제육볶음", candidates)
            then("그 모델 응답은 성분까지 전체 무효 처리되어 유효한 2개로만 종합한다") {
                result.substances shouldContainExactlyInAnyOrder listOf(FoodAvoidanceAssessment("PORK", 85))
                result.spiciness shouldBe 4
            }
        }
    }

    given("한 모델이 맵기를 누락하고 응답") {
        val fanout = fanoutOf(
            AvoidanceCaller(LlmModelId.OPENAI) { assessmentJson(3, "PORK" to 80) },
            AvoidanceCaller(LlmModelId.UPSTAGE) { assessmentJson(4, "PORK" to 90) },
            AvoidanceCaller(LlmModelId.GEMINI) { assessmentJson(null, "PORK" to 100) },
        )
        val client = SpringAiFoodAvoidanceAssessmentClient(fanout)

        `when`("기피성분 조사를 호출하면") {
            val result = client.call("제육볶음", candidates)
            then("누락 모델 응답은 전체 무효 처리된다") {
                result.substances shouldContainExactlyInAnyOrder listOf(FoodAvoidanceAssessment("PORK", 85))
                result.spiciness shouldBe 4
            }
        }
    }

    given("유효 응답이 1개뿐(2개는 예외)") {
        val fanout = fanoutOf(
            AvoidanceCaller(LlmModelId.OPENAI) { assessmentJson(3, "PORK" to 80) },
            AvoidanceCaller(LlmModelId.UPSTAGE) { error("호출 실패") },
            AvoidanceCaller(LlmModelId.GEMINI) { error("호출 실패") },
        )
        val client = SpringAiFoodAvoidanceAssessmentClient(fanout)

        `when`("기피성분 조사를 호출하면") {
            then("종합 불가로 예외를 전파한다") {
                shouldThrow<IllegalArgumentException> { client.call("제육볶음", candidates) }
            }
        }
    }

    given("후보 코드가 빈 집합") {
        val fanout = fanoutOf(
            AvoidanceCaller(LlmModelId.OPENAI) { error("호출되면 안 됨") },
        )
        val client = SpringAiFoodAvoidanceAssessmentClient(fanout)

        `when`("기피성분 조사를 호출하면") {
            then("호출 전제 위반으로 예외가 발생한다") {
                shouldThrow<IllegalArgumentException> { client.call("제육볶음", emptySet()) }
            }
        }
    }
})
