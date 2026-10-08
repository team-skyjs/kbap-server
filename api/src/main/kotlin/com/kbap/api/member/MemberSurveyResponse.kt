package com.kbap.api.member

import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

data class MemberSurveyResponse(
    val ageBand: String,
    val gender: String,
    val acquisition: String,
    val situation: String,
    val tripTiming: String?,
    val tripDuration: String?,
    val purpose: String,
    val foodAffinity: Int,
    @field:Schema(description = "문항 버전 — 문항이 바뀌면 올라간다", example = "1")
    val surveyVersion: Int,
    @field:Schema(description = "마지막으로 답한 시각", example = "2026-10-08T22:08:45.123456")
    val answeredAt: LocalDateTime,
) {
    companion object {
        fun from(result: MemberSurveyResult): MemberSurveyResponse =
            MemberSurveyResponse(
                ageBand = result.answers.ageBand.name,
                gender = result.answers.gender.name,
                acquisition = result.answers.acquisition.name,
                situation = result.answers.situation.name,
                tripTiming = result.answers.tripTiming?.name,
                tripDuration = result.answers.tripDuration?.name,
                purpose = result.answers.purpose.name,
                foodAffinity = result.answers.foodAffinity,
                surveyVersion = result.surveyVersion,
                answeredAt = result.answeredAt,
            )
    }
}
