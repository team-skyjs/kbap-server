package com.kbap.api.member

import com.kbap.common.domain.member.model.Acquisition
import com.kbap.common.domain.member.model.AgeBand
import com.kbap.common.domain.member.model.Gender
import com.kbap.common.domain.member.model.MemberSurvey
import com.kbap.common.domain.member.model.Situation
import com.kbap.common.domain.member.model.SurveyPurpose
import com.kbap.common.domain.member.model.TripDuration
import com.kbap.common.domain.member.model.TripTiming
import java.time.LocalDateTime

data class MemberSurveyResult(
    val ageBand: AgeBand,
    val gender: Gender,
    val acquisition: Acquisition,
    val situation: Situation,
    val tripTiming: TripTiming?,
    val tripDuration: TripDuration?,
    val purpose: SurveyPurpose,
    val foodAffinity: Int,
    val surveyVersion: Int,
    val answeredAt: LocalDateTime,
) {
    companion object {
        fun of(survey: MemberSurvey): MemberSurveyResult =
            MemberSurveyResult(
                ageBand = survey.ageBand,
                gender = survey.gender,
                acquisition = survey.acquisition,
                situation = survey.situation,
                tripTiming = survey.tripTiming,
                tripDuration = survey.tripDuration,
                purpose = survey.purpose,
                foodAffinity = survey.foodAffinity,
                surveyVersion = survey.surveyVersion,
                answeredAt = survey.answeredAt,
            )
    }
}
