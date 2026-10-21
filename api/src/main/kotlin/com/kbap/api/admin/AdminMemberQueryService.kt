package com.kbap.api.admin

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.member.MemberAdRewardJpaRepository
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.member.model.Member
import com.kbap.common.domain.member.model.MemberStatus
import com.kbap.common.domain.member.model.SocialProvider
import com.kbap.common.domain.order.OrderItemJpaRepository
import com.kbap.common.domain.order.OrderJpaRepository
import com.kbap.common.domain.review.ReviewJpaRepository
import com.kbap.common.domain.processedfood.ProcessedFoodProductJpaRepository
import com.kbap.common.domain.scan.ScanHistoryJpaRepository
import com.kbap.common.util.ImageUrls
import com.kbap.common.util.LikeWildcards
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.LocalDate

@Service
class AdminMemberQueryService(
    private val memberRepository: MemberJpaRepository,
    private val reviewRepository: ReviewJpaRepository,
    private val scanHistoryRepository: ScanHistoryJpaRepository,
    private val orderRepository: OrderJpaRepository,
    private val orderItemRepository: OrderItemJpaRepository,
    private val foodRepository: FoodJpaRepository,
    private val adRewardRepository: MemberAdRewardJpaRepository,
    private val processedFoods: ProcessedFoodProductJpaRepository,
    @Value("\${kbap.storage.public-base-url:}") private val imagePublicBaseUrl: String,
) {
    @Transactional(readOnly = true)
    fun searchMemberPage(page: Int, query: String? = null, isBot: Boolean? = null, country: String? = null): AdminMemberListResponse {
        val keyword = query?.trim()?.takeIf { it.isNotEmpty() }
        val countryFilter = countryFilterOf(country)
        val pageable = PageRequest.of(page - 1, PAGE_SIZE)
        val result = when (keyword) {
            null -> memberRepository.findPageAnyStatus(isBot, countryFilter, pageable)
            else -> memberRepository.searchPageAnyStatusByKeyword(
                LikeWildcards.escape(keyword),
                keyword.toLongOrNull() ?: NO_MEMBER_ID,
                isBot,
                countryFilter,
                pageable,
            )
        }
        return AdminMemberListResponse(
            items = result.content.map { AdminMemberListItemResponse.from(it) },
            page = page,
            totalPages = result.totalPages,
            totalCount = result.totalElements,
            hasPrev = page > 1,
            hasNext = page < result.totalPages,
        )
    }

    @Transactional(readOnly = true)
    fun getCountryCounts(isBot: Boolean?): List<AdminMemberCountryCountResponse> =
        memberRepository.countByCountryCodeAnyStatus(isBot).map { AdminMemberCountryCountResponse(it.countryCode, it.count) }

    @Transactional(readOnly = true)
    fun getDailySignups(days: Int, isBot: Boolean?): List<AdminDailyCountResponse> {
        if (days !in SIGNUP_DAYS_RANGE) throw BusinessException(ErrorCode.INVALID_REQUEST)
        val today = LocalDate.now(SEOUL)
        val from = today.minusDays(days - 1L)
        val since = from.atStartOfDay(SEOUL).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
        val counts = memberRepository.findCreatedAtsSinceAnyStatus(since, isBot)
            .groupingBy { it.atZone(ZoneId.systemDefault()).withZoneSameInstant(SEOUL).toLocalDate() }
            .eachCount()
        return (0 until days).map { from.plusDays(it.toLong()) }.map { AdminDailyCountResponse(it, counts[it]?.toLong() ?: 0L) }
    }

    @Transactional(readOnly = true)
    fun getReviewPage(page: Int, isBot: Boolean?): AdminReviewPageResponse {
        val result = reviewRepository.findPageByAuthorBot(isBot, PageRequest.of(page - 1, ACTIVITY_PAGE_SIZE))
        val authors = memberRepository.findAllAnyStatusByIdIn(result.content.map { it.memberId }.distinct().ifEmpty { listOf(NO_MEMBER_ID) }).associateBy { it.id }
        val foodNames = foodNamesOf(result.content.mapNotNull { it.foodId })
        val productNames = processedFoods.findAllById(result.content.mapNotNull { it.processedFoodId }.distinct()).associateBy({ it.id }, { it.productName })
        return AdminReviewPageResponse(
            items = result.content.map { AdminReviewItemResponse.from(it, authors[it.memberId], foodNames, productNames, imagePublicBaseUrl) },
            page = page,
            totalPages = result.totalPages,
            totalCount = result.totalElements,
            hasPrev = page > 1,
            hasNext = page < result.totalPages,
        )
    }

    private fun countryFilterOf(country: String?): String? {
        val value = country?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return null
        if (value != NO_COUNTRY && !value.matches(ISO2)) throw BusinessException(ErrorCode.INVALID_REQUEST)
        return value
    }

    @Transactional(readOnly = true)
    fun getMemberDetail(id: Long): AdminMemberDetailResponse {
        val member = memberRepository.findAnyById(id)
            ?: throw BusinessException(ErrorCode.MEMBER_NOT_FOUND)
        return AdminMemberDetailResponse.from(member, orderRepository.countByMemberId(id), adRewardRepository.countByMemberId(id), imagePublicBaseUrl)
    }

    @Transactional(readOnly = true)
    fun getMemberReviewPage(memberId: Long, page: Int): AdminMemberReviewPageResponse {
        requireMemberExists(memberId)
        val result = reviewRepository.findByMemberId(memberId, activityPageable(page))
        val foodNames = foodNamesOf(result.content.mapNotNull { it.foodId })
        val productNames = processedFoods.findAllById(result.content.mapNotNull { it.processedFoodId }.distinct()).associateBy({ it.id }, { it.productName })
        return AdminMemberReviewPageResponse(
            items = result.content.map { AdminMemberReviewItemResponse.from(it, foodNames, productNames, imagePublicBaseUrl) },
            page = page,
            totalPages = result.totalPages,
            totalCount = result.totalElements,
            hasPrev = page > 1,
            hasNext = page < result.totalPages,
        )
    }

    @Transactional(readOnly = true)
    fun getMemberScanPage(memberId: Long, page: Int): AdminMemberScanPageResponse {
        requireMemberExists(memberId)
        val result = scanHistoryRepository.findByMemberId(memberId, activityPageable(page))
        val foodNames = foodNamesOf(result.content.mapNotNull { it.foodId })
        return AdminMemberScanPageResponse(
            items = result.content.map { AdminMemberScanItemResponse.from(it, foodNames) },
            page = page,
            totalPages = result.totalPages,
            totalCount = result.totalElements,
            hasPrev = page > 1,
            hasNext = page < result.totalPages,
        )
    }

    @Transactional(readOnly = true)
    fun getMemberOrderPage(memberId: Long, page: Int): AdminMemberOrderPageResponse {
        requireMemberExists(memberId)
        val result = orderRepository.findByMemberId(memberId, activityPageable(page))
        val itemsByOrder = orderItemRepository
            .findByOrderIdInOrderByIdAsc(result.content.map { it.id })
            .groupBy { it.orderId }
        return AdminMemberOrderPageResponse(
            items = result.content.map {
                AdminMemberOrderItemResponse.from(it, itemsByOrder[it.id].orEmpty(), imagePublicBaseUrl)
            },
            page = page,
            totalPages = result.totalPages,
            totalCount = result.totalElements,
            hasPrev = page > 1,
            hasNext = page < result.totalPages,
        )
    }

    private fun requireMemberExists(memberId: Long) {
        // existsById 는 @SQLRestriction(ACTIVE) 탓에 탈퇴 회원을 '없음'으로 오판한다 — 전 상태 조회가 맞다.
        memberRepository.findAnyById(memberId) ?: throw BusinessException(ErrorCode.MEMBER_NOT_FOUND)
    }

    private fun activityPageable(page: Int) =
        PageRequest.of(page - 1, ACTIVITY_PAGE_SIZE, Sort.by(Sort.Direction.DESC, "id"))

    private fun foodNamesOf(foodIds: List<Long>): Map<Long, String> =
        foodRepository.findAllById(foodIds.distinct()).associateBy({ it.id }, { it.displayName })

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

    companion object {
        const val PAGE_SIZE = 20

        const val ACTIVITY_PAGE_SIZE = 20

        private const val NO_MEMBER_ID = -1L

        private const val NO_COUNTRY = "NONE"

        private val ISO2 = Regex("[A-Z]{2}")

        private val SIGNUP_DAYS_RANGE = 1..90

        private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
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
                email = member.email,
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
    val providerUid: String,
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
                email = member.email,
                provider = member.provider,
                providerUid = member.providerUid,
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
