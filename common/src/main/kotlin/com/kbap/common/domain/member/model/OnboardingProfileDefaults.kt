package com.kbap.common.domain.member.model

object OnboardingProfileDefaults {
    // 스토리지 키 컨벤션(무슬래시) — MemberProfile 이 저장 시 선행 '/' 를 제거하므로 상수도 슬래시 없이 둔다.
    val PROFILE_IMAGE_PATHS: List<String> = listOf(
        "images/webp/default_profile/avatar-amber.png",
        "images/webp/default_profile/avatar-navy.png",
        "images/webp/default_profile/avatar-olive.png",
        "images/webp/default_profile/avatar-orange.png",
        "images/webp/default_profile/avatar-plum.png",
        "images/webp/default_profile/avatar-teal.png",
    )

    // 시각적으로 혼동되는 0·O·1·I 를 뺀 집합 — 사용자가 닉네임을 옮겨 적을 수 있어야 한다.
    private const val NICKNAME_CODE_CHARS: String = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    private const val NICKNAME_CODE_LENGTH: Int = 6

    fun randomNickname(): String =
        (1..NICKNAME_CODE_LENGTH)
            .map { NICKNAME_CODE_CHARS.random() }
            .joinToString("")

    fun randomProfileImagePath(): String = PROFILE_IMAGE_PATHS.random()
}
