package com.kbap.api.member

import com.kbap.common.domain.member.model.MemberSurvey
import com.kbap.common.domain.member.model.SurveyAnswers
import java.time.LocalDateTime

data class MemberSurveyResult(
    val answers: SurveyAnswers,
    val surveyVersion: Int,
    val answeredAt: LocalDateTime,
) {
    companion object {
        fun of(survey: MemberSurvey): MemberSurveyResult =
            MemberSurveyResult(answers = survey.answers(), surveyVersion = survey.surveyVersion, answeredAt = survey.answeredAt)
    }
}
