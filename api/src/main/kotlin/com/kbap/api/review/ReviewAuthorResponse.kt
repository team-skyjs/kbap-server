package com.kbap.api.review

import com.kbap.common.domain.member.model.Member
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "리뷰 작성자 프로필 — 탈퇴 회원이면 author 자체가 null 로 내려간다")
data class ReviewAuthorResponse(
    @field:Schema(description = "작성자 회원 id", example = "7")
    val memberId: Long,

    @field:Schema(description = "닉네임(미설정이면 null)", example = "먹보")
    val nickname: String?,

    @field:Schema(description = "현재 프로필 국적(ISO-2, 미보유면 null)", example = "VN")
    val countryCode: String?,

    @field:Schema(description = "랭킹 티어 키", example = "GOURMET")
    val tier: String,

    @field:Schema(description = "랭킹 티어 레벨", example = "2")
    val level: Int,

    @field:Schema(description = "랭킹 점수", example = "15")
    val score: Int,
) {
    companion object {
        fun from(member: Member): ReviewAuthorResponse =
            ReviewAuthorResponse(
                memberId = member.id,
                nickname = member.profile.nickname,
                countryCode = member.profile.countryCode?.name,
                tier = member.ranking.tier.key,
                level = member.ranking.tier.level,
                score = member.ranking.score,
            )
    }
}
