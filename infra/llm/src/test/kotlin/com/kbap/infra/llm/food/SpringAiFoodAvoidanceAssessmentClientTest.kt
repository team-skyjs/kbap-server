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

private fun assessmentJson(vararg pairs: Pair<String, Int>): String {
    val items = pairs.joinToString(", ") { "{\"code\": \"${it.first}\", \"inclusionPercent\": ${it.second}}" }
    return """{"assessments": [$items]}"""
}

private fun fanoutOf(vararg callers: LlmModelCaller): LlmFanoutClient =
    LlmFanoutClient(callers.toList(), Executors.newVirtualThreadPerTaskExecutor())

class SpringAiFoodAvoidanceAssessmentClientTest : BehaviorSpec({
    val candidates = setOf("PORK", "BEEF")

    given("3개 모델이 모두 정상 응답") {
        val fanout = fanoutOf(
            AvoidanceCaller(LlmModelId.OPENAI) { assessmentJson("PORK" to 80, "BEEF" to 0) },
            AvoidanceCaller(LlmModelId.UPSTAGE) { assessmentJson("PORK" to 90, "BEEF" to 0) },
            AvoidanceCaller(LlmModelId.GEMINI) { assessmentJson("PORK" to 70, "BEEF" to 0) },
        )
        val client = SpringAiFoodAvoidanceAssessmentClient(fanout)

        `when`("기피성분 조사를 호출하면") {
            val result = client.call("제육볶음", candidates)
            then("코드별 평균으로 종합하고 0 은 제외한다") {
                result shouldContainExactlyInAnyOrder listOf(FoodAvoidanceAssessment("PORK", 80))
            }
        }
    }

    given("한 모델이 후보 밖 코드를 섞어 응답") {
        val fanout = fanoutOf(
            AvoidanceCaller(LlmModelId.OPENAI) { assessmentJson("PORK" to 80) },
            AvoidanceCaller(LlmModelId.UPSTAGE) { assessmentJson("PORK" to 90) },
            AvoidanceCaller(LlmModelId.GEMINI) { assessmentJson("CHICKEN" to 100) },
        )
        val client = SpringAiFoodAvoidanceAssessmentClient(fanout)

        `when`("기피성분 조사를 호출하면") {
            val result = client.call("제육볶음", candidates)
            then("위반 모델은 강등하고 유효한 2개로 종합한다") {
                result shouldContainExactlyInAnyOrder listOf(FoodAvoidanceAssessment("PORK", 85))
            }
        }
    }

    given("유효 응답이 1개뿐(2개는 예외)") {
        val fanout = fanoutOf(
            AvoidanceCaller(LlmModelId.OPENAI) { assessmentJson("PORK" to 80) },
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
            then("LLM 을 호출하지 않고 빈 목록을 반환한다") {
                client.call("제육볶음", emptySet()) shouldBe emptyList()
            }
        }
    }
})
