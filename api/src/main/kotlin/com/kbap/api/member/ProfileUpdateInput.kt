package com.kbap.api.member

data class ProfileUpdateInput(
    val memberId: Long,
    val nickname: String? = null,
    val avoidanceSubstanceCodes: List<String>? = null,
    val dietCategories: List<String>? = null,
    val countryCode: String? = null,
    val profileImageUrl: String? = null,
    val spicinessPreference: String? = null,
    val currency: String? = null,
)
