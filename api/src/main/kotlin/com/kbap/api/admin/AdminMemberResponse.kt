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
    @field:Schema(description = "리뷰 봇 계정 여부", example = "false")
    val isBot: Boolean,
    val createdAt: LocalDateTime,
    @field:Schema(description = "국적 ISO2(미설정 null)", example = "KR", nullable = true)
    val countryCode: String?,
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
                isBot = member.isBot,
                countryCode = member.countryCode,
                createdAt = member.createdAt,
            )
    }
}

data class AdminMemberCountryCountResponse(
    @field:Schema(description = "국적 ISO2(미설정 회원은 null 한 행)", example = "KR", nullable = true)
    val countryCode: String?,
    val count: Long,
    @field:Schema(description = "한국 시간 오늘 가입자 수")
    val todayCount: Long = 0,
    @field:Schema(description = "집계 기준 한국 날짜 YYYY-MM-DD")
    val todayDate: String = "",
)

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
    val scanUnlocked: Boolean,
    val adRewardCount: Long,
    @field:Schema(description = "리뷰 봇 계정 여부", example = "false")
    val isBot: Boolean,
    val reviewCount: Int,
    val orderCount: Long,
    val sanctions: List<String>,
    val createdAt: LocalDateTime,
) {
    companion object {
        fun from(member: Member, orderCount: Long, adRewardCount: Long, imagePublicBaseUrl: String): AdminMemberDetailResponse {
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
                scanUnlocked = member.scanUnlocked,
                adRewardCount = adRewardCount,
                isBot = member.isBot,
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
    val foodId: Long?,
    val foodName: String?,
    val rating: Int,
    val servingSpeedRating: Int,
    val staffKindnessRating: Int,
    val content: String?,
    val imageUrls: List<String>,
    val createdAt: LocalDateTime,
    val targetType: String,
    val processedFoodId: Long?,
    val targetName: String?,
) {
    companion object {
        fun from(review: Review, foodNames: Map<Long, String>, productNames: Map<Long, String>, imagePublicBaseUrl: String): AdminMemberReviewItemResponse =
            AdminMemberReviewItemResponse(
                id = review.id,
                foodId = review.foodId,
                foodName = review.foodId?.let(foodNames::get),
                rating = review.rating,
                servingSpeedRating = review.servingSpeedRating,
                staffKindnessRating = review.staffKindnessRating,
                content = review.content,
                imageUrls = review.imageRefs.orEmpty().mapNotNull { ImageUrls.resolve(imagePublicBaseUrl, it) },
                createdAt = review.createdAt,
                targetType = review.targetType.name,
                processedFoodId = review.processedFoodId,
                targetName = review.foodId?.let(foodNames::get) ?: review.processedFoodId?.let(productNames::get),
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
        description = "이미지 대기인데 진행 중 배치가 없는 음식 수. 다음 일괄 제출이 집어 갈 후보 수와 같다",
        example = "140",
    )
    val pendingImageWithoutBatchCount: Long,
    @field:Schema(
        description = "그중 이미 대표 이미지가 있는 음식 수 — 이미지 재생성이 실패해 숨긴 채 남은 건이다" +
            "(WRONG_IMAGE·의도 누락. REPLACE_BETTER 실패는 READY 로 복원돼 여기 없다). " +
            "사람이 재생성·일괄 제출로 다시 돌려야 하며, 0 이 아니면 확인한다",
        example = "0",
    )
    val strandedImageRegenerationCount: Long,
    @field:Schema(
        description = "콘텐츠 수집 요청을 보낸 뒤 기준 시간(기본 24시간) 안에 응답이 오지 않은 건수. " +
            "다음 배치가 재전송한다(공개(READY) 음식은 재전송하지 않고 포기로 넘긴다). 계속 늘면 수집 파이프라인 쪽을 본다",
        example = "0",
    )
    val contentOutboxStuckCount: Long,
    @field:Schema(
        description = "응답 없는 콘텐츠 요청을 포기한 건수. 두 경우를 함께 센다 — 재전송 상한에 닿은 것, 그리고 음식이 이미 " +
            "공개(READY)라 늦은 결과가 사람의 수정을 덮지 않게 재전송하지 않은 것. **0 이 정상**이며, 0 이 아니면 " +
            "last_error 로 어느 쪽인지 보고, 상한 도달이면 원인을 고친 뒤, 공개 음식이면 현재 내용을 확인한 뒤 필요할 때 재수집한다",
        example = "0",
    )
    val contentOutboxDeadCount: Long,
    @field:Schema(
        description = "미참조 업로드 정리 대상 수(용도별 — review·community·feedback·orders). 보존 기간이 지났고 리뷰·게시글·문의·" +
            "프로필·주문·주문 항목 어디에도 참조되지 않은 업로드다. 실삭제가 꺼진(dry-run) 동안은 쌓이기만 한다. " +
            "업로드가 있는 용도가 0 이면 참조 판정 쿼리를 의심한다. 요청마다 세지 않는다 — 인스턴스가 기동 직후와 매일 " +
            "04:40 KST 에 센 최근값이다(시각은 orphanUploadedImageCountedAt). null 은 0 과 다르다 — 아직 세지 않았거나 " +
            "마지막 계산이 실패해 못 셌다는 뜻이다",
        example = "{\"review\": 12, \"community\": 3, \"feedback\": 0, \"orders\": 2}",
    )
    val orphanUploadedImageCounts: Map<String, Long>?,
    @field:Schema(description = "orphanUploadedImageCounts 를 센 시각. 그 값이 null 이면 이것도 null", example = "2026-09-22T04:40:00")
    val orphanUploadedImageCountedAt: LocalDateTime?,
)

data class AdminDailyCountResponse(
    val date: LocalDate,
    val count: Long,
)

private const val WITHDRAWN_MEMBER_STATUS = "WITHDRAWN"

private fun adminMemberStatusOf(member: Member): String =
    if (member.isDeleted()) WITHDRAWN_MEMBER_STATUS else member.memberStatus.name

@Schema(description = "스캔 무제한 해제 취소 결과")
data class AdminScanUnlockResetResponse(
    @field:Schema(description = "무제한 해제 여부 — 취소 뒤 항상 false", example = "false")
    val scanUnlocked: Boolean,
    @field:Schema(description = "남은 광고 적립 수 — 취소 뒤 항상 0", example = "0")
    val adRewardCount: Int,
    @field:Schema(description = "스캔 수(무료 한도보다 작았으면 한도로 올린다)", example = "3")
    val scanCount: Int,
    @field:Schema(description = "남은 무료 스캔 수", example = "0")
    val scanRemaining: Int,
)
