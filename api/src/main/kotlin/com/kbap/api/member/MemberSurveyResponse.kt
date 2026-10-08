package com.kbap.api.member

import com.kbap.common.domain.member.model.Acquisition
import com.kbap.common.domain.member.model.AgeBand
import com.kbap.common.domain.member.model.Gender
import com.kbap.common.domain.member.model.Situation
import com.kbap.common.domain.member.model.SurveyPurpose
import com.kbap.common.domain.member.model.TripDuration
import com.kbap.common.domain.member.model.TripTiming
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

data class MemberSurveyResponse(
    val ageBand: AgeBand,
    val gender: Gender,
    val acquisition: Acquisition,
    val situation: Situation,
    @field:Schema(description = "situation=TRIP_PLANNED 일 때만 값, 그 외 null", nullable = true)
    val tripTiming: TripTiming?,
    @field:Schema(description = "situation=TRIP_PLANNED·TRAVELING_NOW 일 때만 값, 그 외 null", nullable = true)
    val tripDuration: TripDuration?,
    val purpose: SurveyPurpose,
    @field:Schema(description = "한식 선호 1~5", example = "4")
    val foodAffinity: Int,
    @field:Schema(description = "문항 버전 — 문항이 바뀌면 올라간다", example = "1")
    val surveyVersion: Int,
    @field:Schema(description = "마지막으로 답한 시각", example = "2026-10-08T22:08:45.123456")
    val answeredAt: LocalDateTime,
) {
    companion object {
        fun from(result: MemberSurveyResult): MemberSurveyResponse =
            MemberSurveyResponse(
                ageBand = result.ageBand,
                gender = result.gender,
                acquisition = result.acquisition,
                situation = result.situation,
                tripTiming = result.tripTiming,
                tripDuration = result.tripDuration,
                purpose = result.purpose,
                foodAffinity = result.foodAffinity,
                surveyVersion = result.surveyVersion,
                answeredAt = result.answeredAt,
            )
    }
}
