package com.kbap.api.block

import com.kbap.common.domain.member.model.Member
import com.kbap.common.util.ImageUrls
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "내가 차단한 회원 — 닉네임·프로필 이미지는 조회 시점 최신 값")
data class BlockedMemberResponse(
    @field:Schema(description = "차단한 회원 id", example = "42")
    val memberId: Long,

    @field:Schema(description = "닉네임(미설정이면 null)", example = "먹보")
    val nickname: String?,

    @field:Schema(description = "프로필 이미지 절대 URL(미설정이면 null)", example = "https://cdn.example.com/images/profile/abc.jpg")
    val profileImageUrl: String?,
) {
    companion object {
        fun from(member: Member, imagePublicBaseUrl: String): BlockedMemberResponse =
            BlockedMemberResponse(
                memberId = member.id,
                nickname = member.profile.nickname,
                profileImageUrl = ImageUrls.resolve(imagePublicBaseUrl, member.profile.profileImageUrl),
            )
    }
}
