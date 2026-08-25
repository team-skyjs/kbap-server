package com.kbap.api.admin

import com.kbap.common.domain.member.model.MemberStatus
import com.kbap.common.domain.member.model.SocialProvider
import jakarta.validation.constraints.NotNull
import java.time.LocalDateTime

data class AdminMemberListItemResponse(
    val id: Long,
    val nickname: String?,
    val email: String?,
    val provider: SocialProvider,
    val memberStatus: MemberStatus,
    val onboardingCompleted: Boolean,
    val withdrawn: Boolean,
    val withdrawnAt: LocalDateTime?,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)

data class AdminMemberListResponse(
    val items: List<AdminMemberListItemResponse>,
    val page: Int,
    val size: Int,
    val totalCount: Long,
    val totalPages: Int,
)

data class AdminMemberDetailResponse(
    val id: Long,
    val nickname: String?,
    val email: String?,
    val provider: SocialProvider,
    val memberStatus: MemberStatus,
    val suspendedAt: LocalDateTime?,
    val suspendReason: String?,
    val withdrawn: Boolean,
    val onboardingCompleted: Boolean,
    val profileImageUrl: String?,
    val avoidanceSubstanceCodes: List<String>,
    val dietCategories: List<String>,
    val spicinessPreference: String,
    val countryCode: String?,
    val currency: String?,
    val scan: ScanSection,
    val ranking: RankingSection,
    val activity: ActivitySection,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
) {
    data class ScanSection(val scanCount: Int, val scanUnlocked: Boolean, val scanAllowed: Boolean)
    data class RankingSection(
        val score: Int,
        val tier: String,
        val nextTier: String?,
        val pointsToNext: Int?,
        val reviewCount: Int,
        val uniqueReviewedFoodCount: Int,
    )
    data class ActivitySection(
        val reviewCount: Long,
        val orderCount: Long,
        val scanCount: Long,
        val bookmarkCount: Long,
        val reportsFiled: Long,
        val reportsReceived: Long,
        val blocksCount: Long,
        val recentScans: List<RecentScan>,
        val recentReviews: List<RecentReview>,
        val recentOrders: List<RecentOrder>,
    )
    data class RecentScan(val scanId: Long, val foodId: Long?, val displayName: String?, val createdAt: LocalDateTime)
    data class RecentReview(val reviewId: Long, val foodId: Long, val displayName: String?, val rating: Int, val createdAt: LocalDateTime)
    data class RecentOrder(val orderId: Long, val itemCount: Int, val createdAt: LocalDateTime)
}

data class AdminRankingEventResponse(
    val id: Long,
    val event: String,
    val reviewId: Long,
    val reviewCountDelta: Int,
    val uniqueFoodCountDelta: Int,
    val createdAt: LocalDateTime,
)

data class AdminRankingEventPageResponse(
    val items: List<AdminRankingEventResponse>,
    val page: Int,
    val size: Int,
    val totalCount: Long,
    val totalPages: Int,
)

data class AdminMemberStatusRequest(
    @field:NotNull val memberStatus: MemberStatus?,
    val reason: String? = null,
)

data class AdminMemberProfileResetRequest(
    val resetNickname: Boolean = false,
    val resetProfileImage: Boolean = false,
)

data class AdminMemberActionResponse(
    val id: Long,
    val memberStatus: MemberStatus,
    val nickname: String?,
    val profileImageUrl: String?,
    val scanUnlocked: Boolean,
    val withdrawn: Boolean,
)
