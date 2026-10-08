package com.kbap.common.domain.member.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.LocalDateTime

@Entity
@Table(
    name = "member_survey",
    uniqueConstraints = [UniqueConstraint(name = "uk_member_survey_member", columnNames = ["member_id"])],
)
class MemberSurvey(
    @Column(name = "member_id", nullable = false)
    var memberId: Long = 0,

    @Enumerated(EnumType.STRING)
    @Column(name = "age_band", nullable = false, length = 32)
    var ageBand: AgeBand = AgeBand.TWENTIES,

    @Enumerated(EnumType.STRING)
    @Column(name = "gender", nullable = false, length = 32)
    var gender: Gender = Gender.UNDISCLOSED,

    @Enumerated(EnumType.STRING)
    @Column(name = "acquisition", nullable = false, length = 32)
    var acquisition: Acquisition = Acquisition.OTHER,

    @Enumerated(EnumType.STRING)
    @Column(name = "situation", nullable = false, length = 32)
    var situation: Situation = Situation.INTERESTED_NO_PLAN,

    @Enumerated(EnumType.STRING)
    @Column(name = "trip_timing", length = 32)
    var tripTiming: TripTiming? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "trip_duration", length = 32)
    var tripDuration: TripDuration? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 32)
    var purpose: SurveyPurpose = SurveyPurpose.OTHER,

    @Column(name = "food_affinity", nullable = false, columnDefinition = "TINYINT")
    var foodAffinity: Int = 3,

    @Column(name = "survey_version", nullable = false, columnDefinition = "SMALLINT")
    var surveyVersion: Int = CURRENT_VERSION,

    @Column(name = "answered_at", nullable = false, columnDefinition = "DATETIME(6)")
    var answeredAt: LocalDateTime = LocalDateTime.MIN,
) : BaseEntity() {
    fun answer(answers: SurveyAnswers, answeredAt: LocalDateTime) {
        ageBand = answers.ageBand
        gender = answers.gender
        acquisition = answers.acquisition
        situation = answers.situation
        tripTiming = answers.tripTiming
        tripDuration = answers.tripDuration
        purpose = answers.purpose
        foodAffinity = answers.foodAffinity
        surveyVersion = CURRENT_VERSION
        this.answeredAt = answeredAt
    }

    companion object {
        const val CURRENT_VERSION = 1

        fun answeredBy(memberId: Long, answers: SurveyAnswers, answeredAt: LocalDateTime): MemberSurvey =
            MemberSurvey(memberId = memberId).apply { answer(answers, answeredAt) }
    }
}
