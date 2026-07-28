package com.kbap.core.food

fun interface FoodAvoidanceAssessmentClient {
    // 구현은 3개 모델 API 를 호출해 응답을 종합·최종 판단한다(안전 직결). 결과 code 는 candidateCodes 에 속한다.
    fun call(koreanName: String, candidateCodes: Set<String>): FoodAvoidanceAssessmentResult
}

data class FoodAvoidanceAssessmentResult(
    val substances: List<FoodAvoidanceAssessment>,
    val spiciness: Int,
) {
    init {
        require(spiciness in SPICINESS_RANGE) { "spiciness 는 $SPICINESS_RANGE 여야 합니다: $spiciness" }
    }

    companion object {
        val SPICINESS_RANGE = 0..10
    }
}

data class FoodAvoidanceAssessment(
    val code: String,
    val inclusionPercent: Int,
) {
    init {
        require(code.isNotBlank()) { "code 는 blank 일 수 없습니다" }
        require(inclusionPercent in 0..100) { "inclusionPercent 는 0..100 이어야 합니다: $inclusionPercent" }
    }
}
