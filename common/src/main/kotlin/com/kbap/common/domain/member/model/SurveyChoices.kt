package com.kbap.common.domain.member.model

enum class AgeBand { TEENS, TWENTIES, THIRTIES, FORTIES, FIFTIES_PLUS }

enum class Gender { FEMALE, MALE, OTHER, UNDISCLOSED }

enum class Acquisition { STORE_SEARCH, SNS_AD, FRIEND, BLOG_VIDEO, OTHER }

enum class Situation(val asksTripTiming: Boolean, val asksTripDuration: Boolean) {
    TRAVELING_NOW(asksTripTiming = false, asksTripDuration = true),
    TRIP_PLANNED(asksTripTiming = true, asksTripDuration = true),
    LIVING_IN_KOREA(asksTripTiming = false, asksTripDuration = false),
    INTERESTED_NO_PLAN(asksTripTiming = false, asksTripDuration = false),
}

enum class TripTiming { DATE_FIXED, THIS_YEAR, SOMEDAY }

enum class TripDuration { UP_TO_3_DAYS, ONE_WEEK, TWO_WEEKS, MONTH_PLUS }

enum class SurveyPurpose { MENU_READING, ALLERGY_AVOIDANCE, EXPLORE_FOOD, OTHER }
