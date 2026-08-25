package com.kbap.api.admin

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.block.MemberBlockJpaRepository
import com.kbap.common.domain.bookmark.BookmarkJpaRepository
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.member.AdminMemberFilter
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.member.MemberRankingEventJpaRepository
import com.kbap.common.domain.member.model.Member
import com.kbap.common.domain.member.model.MemberStatus
import com.kbap.common.domain.member.model.SocialProvider
import com.kbap.common.domain.order.OrderItemJpaRepository
import com.kbap.common.domain.order.OrderJpaRepository
import com.kbap.common.domain.report.ReportJpaRepository
import com.kbap.common.domain.review.ReviewJpaRepository
import com.kbap.common.domain.scan.ScanHistoryJpaRepository
import com.kbap.common.util.ImageUrls
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class AdminMemberQueryService(
    private val memberRepository: MemberJpaRepository,
    private val foodRepository: FoodJpaRepository,
    private val reviewRepository: ReviewJpaRepository,
    private val orderRepository: OrderJpaRepository,
    private val orderItemRepository: OrderItemJpaRepository,
    private val scanHistoryRepository: ScanHistoryJpaRepository,
    private val bookmarkRepository: BookmarkJpaRepository,
    private val reportRepository: ReportJpaRepository,
    private val memberBlockRepository: MemberBlockJpaRepository,
    private val rankingEventRepository: MemberRankingEventJpaRepository,
    @Value("\${kbap.storage.public-base-url:}") private val imagePublicBaseUrl: String,
) {
    @Transactional(readOnly = true)
    fun getMemberPage(page: Int): AdminMemberPageView {
        val pageable = PageRequest.of(page - 1, PAGE_SIZE, Sort.by(Sort.Direction.DESC, "id"))
        val result = memberRepository.findAll(pageable)
        return AdminMemberPageView(
            items = result.content.map { AdminMemberSummaryView.from(it) },
            page = page,
            totalPages = result.totalPages,
            totalCount = result.totalElements,
            hasPrev = page > 1,
            hasNext = page < result.totalPages,
        )
    }

    @Transactional(readOnly = true)
    fun getMemberDetailOrNull(id: Long): AdminMemberDetailView? {
        val member = memberRepository.findById(id).orElse(null) ?: return null
        return AdminMemberDetailView.from(member, imagePublicBaseUrl)
    }

    @Transactional(readOnly = true)
    fun getMemberPage(filter: AdminMemberFilter, page: Int, size: Int): AdminMemberListResponse {
        val rows = memberRepository.findAdminPage(filter, page, size)
        return AdminMemberListResponse(
            items = rows.rows.map {
                AdminMemberListItemResponse(
                    id = it.id,
                    nickname = it.nickname,
                    email = maskEmail(it.email),
                    provider = it.provider,
                    memberStatus = it.memberStatus,
                    onboardingCompleted = it.onboardingCompleted,
                    withdrawn = it.withdrawn,
                    withdrawnAt = if (it.withdrawn) it.updatedAt else null,
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt,
                )
            },
            page = page,
            size = size,
            totalCount = rows.totalCount,
            totalPages = totalPagesOf(rows.totalCount, size),
        )
    }

    @Transactional(readOnly = true)
    fun getMemberDetail(id: Long): AdminMemberDetailResponse {
        val member = memberRepository.findByIdIncludingWithdrawn(id) ?: throw BusinessException(ErrorCode.MEMBER_NOT_FOUND)
        val ranking = member.ranking
        val recentScans = scanHistoryRepository.findTop5ByMemberIdOrderByIdDesc(id)
        val recentReviews = reviewRepository.findMemberReviewPage(id, null, PageRequest.of(0, RECENT_LIMIT))
        val recentOrders = orderRepository.findPageByMemberId(id, null, PageRequest.of(0, RECENT_LIMIT))
        val foodNames = foodRepository.findAllById((recentScans.mapNotNull { it.foodId } + recentReviews.map { it.foodId }).toSet())
            .associate { it.id to it.displayName }
        val orderItemCounts = orderItemRepository.findByOrderIdInOrderByIdAsc(recentOrders.map { it.id }).groupingBy { it.orderId }.eachCount()
        return AdminMemberDetailResponse(
            id = member.id,
            nickname = member.nickname,
            email = maskEmail(member.email),
            provider = member.provider,
            memberStatus = member.memberStatus,
            suspendedAt = member.suspendedAt,
            suspendReason = member.suspendReason,
            withdrawn = member.isDeleted(),
            onboardingCompleted = member.onboardingCompleted,
            profileImageUrl = ImageUrls.resolve(imagePublicBaseUrl, member.profileImageUrl),
            avoidanceSubstanceCodes = member.avoidanceSubstanceCodes,
            dietCategories = member.dietCategories,
            spicinessPreference = member.spicinessPreference.name,
            countryCode = member.countryCode,
            currency = member.currency,
            scan = AdminMemberDetailResponse.ScanSection(member.scanCount, member.scanUnlocked, member.isScanAllowed()),
            ranking = AdminMemberDetailResponse.RankingSection(
                score = ranking.score,
                tier = ranking.tier.name,
                nextTier = ranking.nextTier?.name,
                pointsToNext = ranking.pointsToNext,
                reviewCount = member.reviewCount,
                uniqueReviewedFoodCount = member.uniqueReviewedFoodCount,
            ),
            activity = AdminMemberDetailResponse.ActivitySection(
                reviewCount = reviewRepository.countByMemberId(id),
                orderCount = orderRepository.countByMemberId(id),
                scanCount = scanHistoryRepository.countByMemberId(id),
                bookmarkCount = bookmarkRepository.countByMemberId(id),
                reportsFiled = reportRepository.countByReporterMemberId(id),
                reportsReceived = reportRepository.countReceivedByMemberId(id),
                blocksCount = memberBlockRepository.countByBlockerMemberId(id),
                recentScans = recentScans.map { AdminMemberDetailResponse.RecentScan(it.id, it.foodId, it.foodId?.let(foodNames::get), it.createdAt) },
                recentReviews = recentReviews.map { AdminMemberDetailResponse.RecentReview(it.id, it.foodId, foodNames[it.foodId], it.rating, it.createdAt) },
                recentOrders = recentOrders.map { AdminMemberDetailResponse.RecentOrder(it.id, orderItemCounts[it.id] ?: 0, it.createdAt) },
            ),
            createdAt = member.createdAt,
            updatedAt = member.updatedAt,
        )
    }

    @Transactional(readOnly = true)
    fun getRankingEventPage(memberId: Long, page: Int, size: Int): AdminRankingEventPageResponse {
        val result = rankingEventRepository.findByMemberIdOrderByIdDesc(memberId, PageRequest.of(page - 1, size))
        return AdminRankingEventPageResponse(
            items = result.content.map {
                AdminRankingEventResponse(it.id, it.event.name, it.reviewId, it.reviewCountDelta.toInt(), it.uniqueFoodCountDelta.toInt(), it.createdAt)
            },
            page = page,
            size = size,
            totalCount = result.totalElements,
            totalPages = totalPagesOf(result.totalElements, size),
        )
    }

    companion object {
        const val PAGE_SIZE = 20
        const val RECENT_LIMIT = 5

        fun maskEmail(email: String?): String? {
            if (email.isNullOrBlank()) return email
            val at = email.indexOf('@')
            if (at <= 0) return "***"
            val local = email.substring(0, at)
            val visible = local.take(2)
            return "$visible***${email.substring(at)}"
        }
    }
}

data class AdminMemberPageView(
    val items: List<AdminMemberSummaryView>,
    val page: Int,
    val totalPages: Int,
    val totalCount: Long,
    val hasPrev: Boolean,
    val hasNext: Boolean,
)

data class AdminMemberSummaryView(
    val id: Long,
    val nickname: String?,
    val email: String?,
    val provider: SocialProvider,
    val memberStatus: MemberStatus,
    val onboardingCompleted: Boolean,
    val createdAt: LocalDateTime,
) {
    companion object {
        fun from(member: Member): AdminMemberSummaryView =
            AdminMemberSummaryView(
                id = member.id,
                nickname = member.nickname,
                email = AdminMemberQueryService.maskEmail(member.email),
                provider = member.provider,
                memberStatus = member.memberStatus,
                onboardingCompleted = member.onboardingCompleted,
                createdAt = member.createdAt,
            )
    }
}

data class AdminMemberDetailView(
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
    val scanCount: Int,
    val reviewCount: Int,
    val rankingTier: String,
    val createdAt: LocalDateTime,
) {
    companion object {
        fun from(member: Member, imagePublicBaseUrl: String): AdminMemberDetailView {
            val profile = member.profile
            return AdminMemberDetailView(
                id = member.id,
                nickname = member.nickname,
                email = AdminMemberQueryService.maskEmail(member.email),
                provider = member.provider,
                memberStatus = member.memberStatus,
                onboardingCompleted = member.onboardingCompleted,
                profileImageUrl = ImageUrls.resolve(imagePublicBaseUrl, profile.profileImageUrl),
                avoidanceSubstanceCodes = profile.avoidanceSubstanceCodes.map { it.value },
                spicinessPreference = profile.spicinessPreference.name,
                countryCode = profile.countryCode?.name,
                scanCount = member.scanCount,
                reviewCount = member.reviewCount,
                rankingTier = member.ranking.tier.name,
                createdAt = member.createdAt,
            )
        }
    }
}
