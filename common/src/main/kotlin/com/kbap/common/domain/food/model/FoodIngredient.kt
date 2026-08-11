package com.kbap.common.domain.food.model

import com.fasterxml.jackson.annotation.JsonProperty
import com.kbap.common.domain.food.model.RiskLevel

data class FoodIngredient(
    // 생성자 인자 이름을 애너테이션으로 고정한다 — 파라미터 이름 정보 없이 역직렬화하는 매퍼(웹 요청 바인딩)가 있다.
    @JsonProperty("code") val code: String,
    @JsonProperty("inclusion_percent") val inclusionPercent: Int,
) {
    fun riskLevel(): RiskLevel = RiskLevel.fromInclusionProbability(inclusionPercent)
}
