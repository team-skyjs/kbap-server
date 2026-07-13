package com.meogo.domain.member

data class SocialIdentity(
    val provider: SocialProvider,
    val providerUserId: String,
    val email: String?,
) {
    init {
        require(providerUserId.isNotBlank()) { "member.socialIdentity.providerUserId 는 blank 일 수 없습니다" }
        require(providerUserId == providerUserId.trim()) { "member.socialIdentity.providerUserId 는 앞뒤 공백을 가질 수 없습니다" }
    }
}
