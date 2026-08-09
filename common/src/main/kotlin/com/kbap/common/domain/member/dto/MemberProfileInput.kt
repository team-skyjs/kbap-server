package com.kbap.common.domain.member.dto

data class MemberProfileInput(
    val memberId: Long,
    // null = 서버가 온보딩 시 지정한다(v2 경로). v1 요청 DTO 는 non-null 이라 null 이 도달하지 않는다.
    val nickname: String? = null,
    val avoidanceSubstanceCodes: List<String>,
    val countryCode: String,
    val profileImageUrl: String? = null,
    val spicinessPreference: String,
)
