package com.meogo.application.client.member.dto

data class ProfileUpdateInput(
    val memberId: Long,
    val nickname: String? = null,
    val avoidanceSubstanceCodes: List<String>? = null,
    val countryCode: String? = null,
    val appLanguage: String? = null,
)
