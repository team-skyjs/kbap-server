package com.meogo.application.member.dto

data class ProfileUpdateInput(
    val memberId: Long,
    val nickname: String? = null,
    val avoidanceSubstanceCodes: List<String>? = null,
    val countryCode: String? = null,
    val appLanguage: String? = null,
)
