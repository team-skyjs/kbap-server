package com.meogo.core.research

data class CandidateSubstance(
    val code: String,
    val koreanLabel: String,
) {
    init {
        require(code.matches(CODE_FORMAT)) { "research.candidateSubstance.code 는 대문자·숫자·underscore 형식이어야 합니다" }
        require(koreanLabel.isNotBlank()) { "research.candidateSubstance.koreanLabel 은 blank 일 수 없습니다" }
    }

    companion object {
        private val CODE_FORMAT = Regex("^[A-Z0-9_]+$")
    }
}
