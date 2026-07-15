package com.kbap.domain.member.dto

data class ProfileUpdateInput(
    val memberId: Long,
    val nickname: String? = null,
    val avoidanceSubstanceCodes: List<String>? = null,
    val countryCode: String? = null,
    val appLanguage: String? = null,
    val profileImageUrl: String? = null,
    val spicinessPreference: Int? = null,
)
