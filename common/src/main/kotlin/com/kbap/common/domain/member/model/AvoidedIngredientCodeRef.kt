package com.kbap.common.domain.member.model

data class AvoidedIngredientCodeRef(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "member.avoidedIngredientCodeRef 는 blank 일 수 없습니다" }
        require(value == value.trim()) { "member.avoidedIngredientCodeRef 는 앞뒤 공백을 가질 수 없습니다" }
        require(value.matches(FORMAT)) { "member.avoidedIngredientCodeRef 는 대문자·숫자·underscore 형식이어야 합니다" }
    }

    companion object {
        private val FORMAT = Regex("^[A-Z0-9_]+$")
    }
}
