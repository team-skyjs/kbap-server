package com.kbap.api.admin

import com.kbap.common.domain.member.model.Member
import com.kbap.common.domain.member.model.MemberStatus
import com.kbap.common.domain.member.model.SocialProvider
import com.kbap.common.domain.order.model.Order
import com.kbap.common.domain.review.model.Review
import com.kbap.common.domain.scan.model.ScanHistory
import com.kbap.common.util.ImageUrls
import java.time.LocalDate
import java.time.LocalDateTime

data class AdminMemberListResponse(
    val items: List<AdminMemberListItemResponse>,
    val page: Int,
    val totalPages: Int,
    val totalCount: Long,
    val hasPrev: Boolean,
    val hasNext: Boolean,
)

data class AdminMemberListItemResponse(
    val id: Long,
    val nickname: String?,
    val email: String?,
    val provider: SocialProvider,
    val memberStatus: MemberStatus,
    val onboardingCompleted: Boolean,
    val createdAt: LocalDateTime,
) {
    companion object {
        fun from(member: Member): AdminMemberListItemResponse =
            AdminMemberListItemResponse(
                id = member.id,
                nickname = member.nickname,
                email = member.email,
                provider = member.provider,
                memberStatus = member.memberStatus,
                onboardingCompleted = member.onboardingCompleted,
                createdAt = member.createdAt,
            )
    }
}

data class AdminMemberDetailResponse(
    val id: Long,
    val nickname: String?,
    val email: String?,
    val provider: SocialProvider,
    val memberStatus: MemberStatus,
    val onboardingCompleted: Boolean,
    val profileImageUrl: String?,
    val avoidanceSubstanceCodes: List<String>,
    val spicinessPreference: String,
    val countryCode: String?,
    val rankingTier: String,
    val scanCount: Int,
    val reviewCount: Int,
    val orderCount: Long,
    val sanctions: List<String>,
    val createdAt: LocalDateTime,
) {
    companion object {
        fun from(member: Member, orderCount: Long, imagePublicBaseUrl: String): AdminMemberDetailResponse {
            val profile = member.profile
            return AdminMemberDetailResponse(
                id = member.id,
                nickname = member.nickname,
                email = member.email,
                provider = member.provider,
                memberStatus = member.memberStatus,
                onboardingCompleted = member.onboardingCompleted,
                profileImageUrl = ImageUrls.resolve(imagePublicBaseUrl, profile.profileImageUrl),
                avoidanceSubstanceCodes = profile.avoidanceSubstanceCodes.map { it.value },
                spicinessPreference = profile.spicinessPreference.name,
                countryCode = profile.countryCode?.name,
                rankingTier = member.ranking.tier.name,
                scanCount = member.scanCount,
                reviewCount = member.reviewCount,
                orderCount = orderCount,
                sanctions = emptyList(),
                createdAt = member.createdAt,
            )
        }
    }
}

data class AdminMemberReviewPageResponse(
    val items: List<AdminMemberReviewItemResponse>,
    val page: Int,
    val totalPages: Int,
    val totalCount: Long,
    val hasPrev: Boolean,
    val hasNext: Boolean,
)

data class AdminMemberReviewItemResponse(
    val id: Long,
    val foodId: Long,
    val foodName: String?,
    val rating: Int,
    val servingSpeedRating: Int,
    val staffKindnessRating: Int,
    val content: String?,
    val imageUrls: List<String>,
    val createdAt: LocalDateTime,
) {
    companion object {
        fun from(review: Review, foodNames: Map<Long, String>, imagePublicBaseUrl: String): AdminMemberReviewItemResponse =
            AdminMemberReviewItemResponse(
                id = review.id,
                foodId = review.foodId,
                foodName = foodNames[review.foodId],
                rating = review.rating,
                servingSpeedRating = review.servingSpeedRating,
                staffKindnessRating = review.staffKindnessRating,
                content = review.content,
                imageUrls = review.imageRefs.orEmpty().mapNotNull { ImageUrls.resolve(imagePublicBaseUrl, it) },
                createdAt = review.createdAt,
            )
    }
}

data class AdminMemberScanPageResponse(
    val items: List<AdminMemberScanItemResponse>,
    val page: Int,
    val totalPages: Int,
    val totalCount: Long,
    val hasPrev: Boolean,
    val hasNext: Boolean,
)

data class AdminMemberScanItemResponse(
    val id: Long,
    val foodId: Long?,
    val foodName: String?,
    val price: Int?,
    val createdAt: LocalDateTime,
) {
    companion object {
        fun from(scan: ScanHistory, foodNames: Map<Long, String>): AdminMemberScanItemResponse =
            AdminMemberScanItemResponse(
                id = scan.id,
                foodId = scan.foodId,
                foodName = scan.foodId?.let { foodNames[it] },
                price = scan.price,
                createdAt = scan.createdAt,
            )
    }
}

data class AdminMemberOrderPageResponse(
    val items: List<AdminMemberOrderItemResponse>,
    val page: Int,
    val totalPages: Int,
    val totalCount: Long,
    val hasPrev: Boolean,
    val hasNext: Boolean,
)

data class AdminMemberOrderItemResponse(
    val id: Long,
    val imageUrl: String?,
    val roadAddress: String?,
    val createdAt: LocalDateTime,
) {
    companion object {
        fun from(order: Order, imagePublicBaseUrl: String): AdminMemberOrderItemResponse =
            AdminMemberOrderItemResponse(
                id = order.id,
                imageUrl = ImageUrls.resolve(imagePublicBaseUrl, order.imagePath),
                roadAddress = order.roadAddress,
                createdAt = order.createdAt,
            )
    }
}

data class AdminDashboardMetricsResponse(
    val totalActiveMembers: Long,
    val pendingReviewCount: Long,
    val weeklyScanCount: Long,
    val prevWeekScanCount: Long,
    val weeklyScans: List<AdminDailyCountResponse>,
)

data class AdminDailyCountResponse(
    val date: LocalDate,
    val count: Long,
)
