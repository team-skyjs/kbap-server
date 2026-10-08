package com.kbap.common.domain.member.model

class SurveyAnswers(
    val ageBand: AgeBand,
    val gender: Gender,
    val acquisition: Acquisition,
    val situation: Situation,
    tripTiming: TripTiming?,
    tripDuration: TripDuration?,
    val purpose: SurveyPurpose,
    val foodAffinity: Int,
) {
    val tripTiming: TripTiming? = tripTiming.takeIf { situation.asksTripTiming }
    val tripDuration: TripDuration? = tripDuration.takeIf { situation.asksTripDuration }

    init {
        require(!situation.asksTripTiming || tripTiming != null) { "tripTiming 은 situation=$situation 에서 필수입니다" }
        require(!situation.asksTripDuration || tripDuration != null) { "tripDuration 은 situation=$situation 에서 필수입니다" }
        require(foodAffinity in FOOD_AFFINITY_RANGE) { "foodAffinity 는 ${FOOD_AFFINITY_RANGE.first}~${FOOD_AFFINITY_RANGE.last} 사이여야 합니다" }
    }

    companion object {
        val FOOD_AFFINITY_RANGE = 1..5
    }
}
