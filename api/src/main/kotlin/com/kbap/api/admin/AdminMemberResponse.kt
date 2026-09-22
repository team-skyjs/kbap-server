package com.kbap.api.admin

import com.kbap.common.domain.member.model.Member
import com.kbap.common.domain.member.model.SocialProvider
import com.kbap.common.domain.order.model.Order
import com.kbap.common.domain.order.model.OrderItem
import com.kbap.common.domain.review.model.Review
import com.kbap.common.domain.scan.model.ScanHistory
import com.kbap.common.util.ImageUrls
import io.swagger.v3.oas.annotations.media.Schema
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
    val memberStatus: String,
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
                memberStatus = adminMemberStatusOf(member),
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
    val memberStatus: String,
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
                memberStatus = adminMemberStatusOf(member),
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
    val items: List<AdminMemberOrderFoodResponse>,
    val totalQuantity: Int,
    val totalPrice: Long,
    val createdAt: LocalDateTime,
) {
    companion object {
        fun from(order: Order, orderItems: List<OrderItem>, imagePublicBaseUrl: String): AdminMemberOrderItemResponse =
            AdminMemberOrderItemResponse(
                id = order.id,
                imageUrl = ImageUrls.resolve(imagePublicBaseUrl, order.imagePath),
                roadAddress = order.roadAddress,
                items = orderItems.map(AdminMemberOrderFoodResponse::from),
                totalQuantity = OrderItem.totalQuantityOf(orderItems),
                totalPrice = OrderItem.totalPriceLongOf(orderItems),
                createdAt = order.createdAt,
            )
    }
}

data class AdminMemberOrderFoodResponse(
    val foodId: Long,
    val menuName: String,
    val quantity: Int,
    val price: Int?,
) {
    companion object {
        fun from(item: OrderItem): AdminMemberOrderFoodResponse =
            AdminMemberOrderFoodResponse(
                foodId = item.foodId,
                menuName = item.menuName,
                quantity = item.quantity,
                price = item.price,
            )
    }
}

data class AdminDashboardMetricsResponse(
    val totalActiveMembers: Long,
    val pendingReviewCount: Long,
    val weeklyScanCount: Long,
    val prevWeekScanCount: Long,
    val weeklyScans: List<AdminDailyCountResponse>,
    @field:Schema(
        description = "미참조 업로드 정리 대상 수(용도별 — review·community·feedback). 보존 기간이 지났고 리뷰·게시글·문의·" +
            "프로필·주문 어디에도 참조되지 않은 업로드다. 실삭제가 꺼진(dry-run) 동안은 쌓이기만 한다. " +
            "업로드가 있는 용도가 0 이면 참조 판정 쿼리를 의심한다. null 은 0 과 다르다 — 건수 조회가 실패해 세지 못했다는 뜻이며, " +
            "이때도 나머지 지표는 정상으로 내려간다",
        example = "{\"review\": 12, \"community\": 3, \"feedback\": 0}",
    )
    val orphanUploadedImageCounts: Map<String, Long>?,
)

data class AdminDailyCountResponse(
    val date: LocalDate,
    val count: Long,
)

private const val WITHDRAWN_MEMBER_STATUS = "WITHDRAWN"

private fun adminMemberStatusOf(member: Member): String =
    if (member.isDeleted()) WITHDRAWN_MEMBER_STATUS else member.memberStatus.name
