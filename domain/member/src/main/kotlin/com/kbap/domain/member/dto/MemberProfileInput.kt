package com.kbap.domain.member.dto

data class MemberProfileInput(
    val memberId: Long,
    val nickname: String,
    val avoidanceSubstanceCodes: List<String>,
    val countryCode: String,
    val appLanguage: String,
    val profileImageUrl: String? = null,
    val spicinessPreference: Int,
)
