package com.kbap.common.domain.member.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class SurveyAnswersTest : BehaviorSpec({

    fun answers(
        situation: Situation = Situation.LIVING_IN_KOREA,
        tripTiming: TripTiming? = null,
        tripDuration: TripDuration? = null,
        foodAffinity: Int = 3,
    ) = SurveyAnswers(
        ageBand = AgeBand.TWENTIES,
        gender = Gender.FEMALE,
        acquisition = Acquisition.SNS_AD,
        situation = situation,
        tripTiming = tripTiming,
        tripDuration = tripDuration,
        purpose = SurveyPurpose.MENU_READING,
        foodAffinity = foodAffinity,
    )

    given("설문 응답 조립") {
        `when`("여행 예정(TRIP_PLANNED)인데 여행 시기가 없으면") {
            then("거절한다") {
                shouldThrow<IllegalArgumentException> { answers(Situation.TRIP_PLANNED, tripTiming = null, tripDuration = TripDuration.ONE_WEEK) }
                    .message shouldContain "tripTiming"
            }
        }
        `when`("여행 예정(TRIP_PLANNED)인데 여행 기간이 없으면") {
            then("거절한다") {
                shouldThrow<IllegalArgumentException> { answers(Situation.TRIP_PLANNED, tripTiming = TripTiming.THIS_YEAR, tripDuration = null) }
                    .message shouldContain "tripDuration"
            }
        }
        `when`("여행 중(TRAVELING_NOW)이면") {
            then("여행 기간은 필수, 여행 시기는 비워야 한다") {
                answers(Situation.TRAVELING_NOW, tripDuration = TripDuration.UP_TO_3_DAYS).tripDuration shouldBe TripDuration.UP_TO_3_DAYS
                shouldThrow<IllegalArgumentException> { answers(Situation.TRAVELING_NOW, tripDuration = null) }
                shouldThrow<IllegalArgumentException> { answers(Situation.TRAVELING_NOW, tripTiming = TripTiming.SOMEDAY, tripDuration = TripDuration.ONE_WEEK) }
            }
        }
        `when`("거주 중·계획 없음이면") {
            then("여행 시기·기간을 보내면 거절하고, 비우면 받는다") {
                answers(Situation.LIVING_IN_KOREA).tripTiming shouldBe null
                answers(Situation.INTERESTED_NO_PLAN).tripDuration shouldBe null
                shouldThrow<IllegalArgumentException> { answers(Situation.LIVING_IN_KOREA, tripDuration = TripDuration.MONTH_PLUS) }
                shouldThrow<IllegalArgumentException> { answers(Situation.INTERESTED_NO_PLAN, tripTiming = TripTiming.SOMEDAY) }
            }
        }
        `when`("한식 선호가 1~5 를 벗어나면") {
            then("거절한다") {
                shouldThrow<IllegalArgumentException> { answers(foodAffinity = 0) }.message shouldContain "foodAffinity"
                shouldThrow<IllegalArgumentException> { answers(foodAffinity = 6) }
                answers(foodAffinity = 1).foodAffinity shouldBe 1
                answers(foodAffinity = 5).foodAffinity shouldBe 5
            }
        }
    }
})
