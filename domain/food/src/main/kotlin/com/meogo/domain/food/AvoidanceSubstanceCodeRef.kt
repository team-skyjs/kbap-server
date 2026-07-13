package com.meogo.domain.food

data class AvoidanceSubstanceCodeRef(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "food.avoidanceSubstanceCodeRef 는 blank 일 수 없습니다" }
        require(value == value.trim()) { "food.avoidanceSubstanceCodeRef 는 앞뒤 공백을 가질 수 없습니다" }
        require(value.matches(FORMAT)) { "food.avoidanceSubstanceCodeRef 는 대문자·숫자·underscore 형식이어야 합니다" }
    }

    companion object {
        private val FORMAT = Regex("^[A-Z0-9_]+$")
    }
}
