package com.kbap.common.domain.member.model

data class SurveyAnswers(
    val ageBand: AgeBand,
    val gender: Gender,
    val acquisition: Acquisition,
    val situation: Situation,
    val tripTiming: TripTiming?,
    val tripDuration: TripDuration?,
    val purpose: SurveyPurpose,
    val foodAffinity: Int,
) {
    init {
        require((tripTiming != null) == situation.asksTripTiming) {
            if (situation.asksTripTiming) "tripTiming 은 situation=$situation 에서 필수입니다" else "tripTiming 은 situation=$situation 에서 비워야 합니다"
        }
        require((tripDuration != null) == situation.asksTripDuration) {
            if (situation.asksTripDuration) "tripDuration 은 situation=$situation 에서 필수입니다" else "tripDuration 은 situation=$situation 에서 비워야 합니다"
        }
        require(foodAffinity in FOOD_AFFINITY_RANGE) { "foodAffinity 는 ${FOOD_AFFINITY_RANGE.first}~${FOOD_AFFINITY_RANGE.last} 사이여야 합니다" }
    }

    companion object {
        val FOOD_AFFINITY_RANGE = 1..5
    }
}
