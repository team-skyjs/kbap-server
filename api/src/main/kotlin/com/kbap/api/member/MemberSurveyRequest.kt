package com.kbap.api.member

import com.kbap.common.domain.member.model.Acquisition
import com.kbap.common.domain.member.model.AgeBand
import com.kbap.common.domain.member.model.Gender
import com.kbap.common.domain.member.model.Situation
import com.kbap.common.domain.member.model.SurveyAnswers
import com.kbap.common.domain.member.model.SurveyPurpose
import com.kbap.common.domain.member.model.TripDuration
import com.kbap.common.domain.member.model.TripTiming
import io.swagger.v3.oas.annotations.media.Schema

data class MemberSurveyRequest(
    @field:Schema(description = "나이대", example = "TWENTIES")
    val ageBand: AgeBand,
    @field:Schema(description = "성별", example = "FEMALE")
    val gender: Gender,
    @field:Schema(description = "유입 경로", example = "SNS_AD")
    val acquisition: Acquisition,
    @field:Schema(description = "지금 상황", example = "TRIP_PLANNED")
    val situation: Situation,
    @field:Schema(description = "여행 시기 — situation=TRIP_PLANNED 일 때만 필수, 그 외 null", example = "THIS_YEAR", nullable = true)
    val tripTiming: TripTiming? = null,
    @field:Schema(description = "여행 기간 — situation=TRIP_PLANNED·TRAVELING_NOW 일 때 필수, 그 외 null", example = "ONE_WEEK", nullable = true)
    val tripDuration: TripDuration? = null,
    @field:Schema(description = "사용 목적", example = "MENU_READING")
    val purpose: SurveyPurpose,
    @field:Schema(description = "한식 선호 1~5", example = "4", minimum = "1", maximum = "5")
    val foodAffinity: Int,
) {
    fun toAnswers(): SurveyAnswers =
        SurveyAnswers(
            ageBand = ageBand,
            gender = gender,
            acquisition = acquisition,
            situation = situation,
            tripTiming = tripTiming,
            tripDuration = tripDuration,
            purpose = purpose,
            foodAffinity = foodAffinity,
        )
}
