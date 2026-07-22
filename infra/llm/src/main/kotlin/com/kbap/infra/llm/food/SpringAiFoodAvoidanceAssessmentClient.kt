package com.kbap.infra.llm.food

import com.kbap.core.food.FoodAvoidanceAssessment
import com.kbap.core.food.FoodAvoidanceAssessmentClient
import com.kbap.infra.llm.client.LlmFanoutClient
import com.kbap.infra.llm.model.LlmChatRequest
import kotlin.math.roundToInt

// 안전 직결 — 유효 모델 응답이 minAgreement 미만이면 종합하지 않고 예외(단일 모델 판단 금지).
class SpringAiFoodAvoidanceAssessmentClient(
    private val fanoutClient: LlmFanoutClient,
    private val parser: FoodContentJsonParser = FoodContentJsonParser(),
    private val minAgreement: Int = 2,
) : FoodAvoidanceAssessmentClient {

    override fun call(koreanName: String, candidateCodes: Set<String>): List<FoodAvoidanceAssessment> {
        if (candidateCodes.isEmpty()) return emptyList()

        val fanout = fanoutClient.generate(LlmChatRequest(prompt = promptOf(koreanName, candidateCodes), system = SYSTEM_PROMPT))
        val validResponses = fanout.successes.mapNotNull { parseValidOrNull(it.content, candidateCodes) }
        require(validResponses.size >= minAgreement) {
            "기피성분 조사를 종합할 유효 모델 응답이 부족합니다: ${validResponses.size}/${fanout.attemptedCount()} (최소 $minAgreement)"
        }
        return aggregate(candidateCodes, validResponses)
    }

    private fun parseValidOrNull(raw: String, candidateCodes: Set<String>): Map<String, Int>? =
        try {
            val response = parser.parse<AssessmentResponse>(raw)
            val byCode = mutableMapOf<String, Int>()
            for (item in response.assessments) {
                if (item.code !in candidateCodes) return null
                if (item.inclusionPercent !in 0..100) return null
                byCode[item.code] = item.inclusionPercent
            }
            byCode
        } catch (e: FoodContentParseException) {
            null
        }

    private fun aggregate(candidateCodes: Set<String>, responses: List<Map<String, Int>>): List<FoodAvoidanceAssessment> =
        candidateCodes.mapNotNull { code ->
            val avg = responses.map { it[code] ?: 0 }.average().roundToInt()
            if (avg == 0) null else FoodAvoidanceAssessment(code, avg)
        }

    private fun promptOf(koreanName: String, candidateCodes: Set<String>): String =
        """
        아래 한국 음식에 특정 기피성분이 얼마나 포함되는지 판단하라.
        음식명: "$koreanName"

        후보 기피성분 코드: ${candidateCodes.joinToString(", ")}

        규칙:
        - 위 후보 코드 각각에 대해 포함 정도를 0(전혀 없음)~100(확실히 포함) 정수로 판단한다.
        - 포함되지 않는 성분은 0 으로 둔다. 후보에 없는 코드는 절대 넣지 마라.
        - 아래 JSON 형식으로만 답하라(설명·코드펜스 없이):
        {"assessments": [{"code": "PORK", "inclusionPercent": 80}, {"code": "BEEF", "inclusionPercent": 0}]}
        """.trimIndent()

    data class AssessmentResponse(val assessments: List<AssessmentItem> = emptyList())

    data class AssessmentItem(val code: String = "", val inclusionPercent: Int = -1)

    companion object {
        private const val SYSTEM_PROMPT = "너는 음식의 성분을 분석하는 식품 전문가다. 반드시 지정된 JSON 형식으로만 답한다."
    }
}
