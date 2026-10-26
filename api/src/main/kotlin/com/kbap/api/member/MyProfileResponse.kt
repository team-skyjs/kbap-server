package com.kbap.api.member

import java.time.Instant
import io.swagger.v3.oas.annotations.media.Schema

data class MyProfileResponse(
    val memberId: Long,
    val provider: String,
    val nickname: String?,
    val avoidanceSubstanceCodes: List<String>,
    val dietCategories: List<String>,
    val countryCode: String?,
    val profileImageUrl: String?,
    val spicinessPreference: String,
    val currency: String?,
    val onboardingCompleted: Boolean,
    @field:Schema(description = "누적 스캔 횟수", example = "1")
    val scanCount: Int,
    @field:Schema(description = "무료 스캔 한도(현재 3)", example = "3")
    val freeScanLimit: Int,
    @field:Schema(description = "스캔 무제한 해금 여부. 무제한 정본 = 이 값(true 면 scanRemaining=null)", example = "false")
    val scanUnlocked: Boolean,
    @field:Schema(description = "잔여 무료 스캔 횟수. scanUnlocked=true(무제한)면 null", example = "2", nullable = true)
    val scanRemaining: Int?,
    val ranking: RankingSummary,
    @field:Schema(description = "보상형 광고 누적 적립 수(서버 정본). 앱은 adRewardCount/adRewardsForUnlock 로 n/3 표시", example = "1")
    val adRewardCount: Int,
    @field:Schema(description = "스캔 해제에 필요한 광고 적립 수(현재 3)", example = "3")
    val adRewardsForUnlock: Int,
    @field:Schema(description = "해제까지 남은 광고 적립 수. scanUnlocked=true(리뷰로 해제된 경우 포함)면 항상 0 — 앱은 이 값이 0 이면 n/3 카드를 그리지 않는다", example = "2")
    val adRewardRemainingForUnlock: Int,
    @field:Schema(description = "가공식품 1시간 무료 창이 끝나는 시각(ISO) — 앱이 카메라 진입 때 읽는다. 차감된 가공식품 스캔 성공부터 고정 60분, 창 안의 가공식품 스캔은 차감 0·남은 횟수 0 이어도 통과(메뉴판 스캔은 대상 아님). 창이 없으면 null", example = "2026-10-27T04:00:00Z", nullable = true)
    val freeUntil: Instant? = null,
) {
    data class RankingSummary(
        val tier: String,
        val level: Int,
        val score: Int,
        val nextTier: String?,
        val pointsToNext: Int?,
    ) {
        companion object {
            fun from(result: MemberRankingResult): RankingSummary =
                RankingSummary(
                    tier = result.tier,
                    level = result.level,
                    score = result.score,
                    nextTier = result.nextTier,
                    pointsToNext = result.pointsToNext,
                )
        }
    }

    companion object {
        fun from(result: MyProfileResult): MyProfileResponse =
            MyProfileResponse(
                memberId = result.memberId,
                provider = result.provider,
                nickname = result.nickname,
                avoidanceSubstanceCodes = result.avoidanceSubstanceCodes,
                dietCategories = result.dietCategories,
                countryCode = result.countryCode,
                profileImageUrl = result.profileImageUrl,
                spicinessPreference = result.spicinessPreference,
                currency = result.currency,
                onboardingCompleted = result.onboardingCompleted,
                scanCount = result.scanCount,
                freeScanLimit = result.freeScanLimit,
                scanUnlocked = result.scanUnlocked,
                scanRemaining = result.scanRemaining,
                ranking = RankingSummary.from(result.ranking),
                adRewardCount = result.adRewardCount,
                adRewardsForUnlock = result.adRewardsForUnlock,
                adRewardRemainingForUnlock = result.adRewardRemainingForUnlock,
            )
    }
}
