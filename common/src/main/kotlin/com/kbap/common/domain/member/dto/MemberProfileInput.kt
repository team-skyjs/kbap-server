package com.kbap.common.domain.member.dto

data class MemberProfileInput(
    val memberId: Long,
    // null = 서버가 온보딩 시 랜덤 지정한다(닉네임 코드·기본 아바타) — X-App-Version >= 1.1.0 신버전 앱 경로.
    val nickname: String? = null,
    val avoidanceSubstanceCodes: List<String>,
    val countryCode: String,
    val profileImageUrl: String? = null,
    val spicinessPreference: String,
)
