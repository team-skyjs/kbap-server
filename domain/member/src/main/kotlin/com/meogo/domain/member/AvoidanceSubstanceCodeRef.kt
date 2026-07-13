package com.meogo.domain.member

data class AvoidanceSubstanceCodeRef(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "member.avoidanceSubstanceCodeRef 는 blank 일 수 없습니다" }
        require(value == value.trim()) { "member.avoidanceSubstanceCodeRef 는 앞뒤 공백을 가질 수 없습니다" }
        require(value.matches(FORMAT)) { "member.avoidanceSubstanceCodeRef 는 대문자·숫자·underscore 형식이어야 합니다" }
    }

    companion object {
        private val FORMAT = Regex("^[A-Z0-9_]+$")
    }
}
