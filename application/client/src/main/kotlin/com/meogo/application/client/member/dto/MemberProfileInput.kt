package com.meogo.application.client.member.dto

data class MemberProfileInput(
    val memberId: Long,
    val nickname: String,
    val avoidanceSubstanceCodes: List<String>,
    val countryCode: String,
    val appLanguage: String,
)
