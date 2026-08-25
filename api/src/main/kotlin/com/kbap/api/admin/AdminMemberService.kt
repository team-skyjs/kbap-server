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
import com.kbap.common.port.auth.SocialAccountDeleter
import com.kbap.common.domain.admin.model.AdminAuditAction
import com.kbap.common.domain.admin.model.AdminAuditTargetType
import com.kbap.common.domain.order.OrderItemJpaRepository
import com.kbap.common.domain.order.OrderJpaRepository
import com.kbap.common.domain.report.ReportJpaRepository
import com.kbap.common.domain.review.ReviewJpaRepository
import com.kbap.common.domain.scan.ScanHistoryJpaRepository
import com.kbap.common.util.ImageUrls
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime

@Service
class AdminMemberService(
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
    private val socialAccountDeleter: SocialAccountDeleter,
    private val auditRecorder: AdminAuditRecorder,
    transactionManager: PlatformTransactionManager,
    @Value("\${kbap.storage.public-base-url:}") private val imagePublicBaseUrl: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val transaction = TransactionTemplate(transactionManager)

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
    fun getMemberDetail(id: Long): AdminMemberDetailResponse = toDetail(getMemberIncludingWithdrawn(id), reveal = false)

    @Transactional
    fun revealMemberDetail(adminId: Long, id: Long): AdminMemberDetailResponse {
        val member = getMemberIncludingWithdrawn(id)
        auditRecorder.record(adminId, AdminAuditAction.MEMBER_PII_REVEAL, AdminAuditTargetType.MEMBER, member.id, null, null)
        return toDetail(member, reveal = true)
    }

    private fun getMemberIncludingWithdrawn(id: Long): Member =
        memberRepository.findByIdIncludingWithdrawn(id) ?: throw BusinessException(ErrorCode.MEMBER_NOT_FOUND)

    private fun toDetail(member: Member, reveal: Boolean): AdminMemberDetailResponse {
        val id = member.id
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
            email = if (reveal) member.email else maskEmail(member.email),
            providerUid = if (reveal) member.providerUid else null,
            revealed = reveal,
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

    @Transactional
    fun changeStatus(adminId: Long, memberId: Long, status: MemberStatus, reason: String?): AdminMemberActionResponse {
        val member = getMember(memberId)
        val before = member.memberStatus
        when (status) {
            MemberStatus.SUSPENDED -> {
                if (reason.isNullOrBlank()) throw BusinessException(ErrorCode.INVALID_REQUEST)
                member.suspend(reason.trim())
            }
            MemberStatus.ACTIVE -> member.reinstate()
        }
        if (before != member.memberStatus) {
            auditRecorder.record(
                adminId, AdminAuditAction.MEMBER_STATUS, AdminAuditTargetType.MEMBER, member.id,
                mapOf("memberStatus" to before.name), mapOf("memberStatus" to member.memberStatus.name), note = reason,
            )
        }
        return toActionResponse(member)
    }

    @Transactional
    fun resetProfile(adminId: Long, memberId: Long, resetNickname: Boolean, resetProfileImage: Boolean): AdminMemberActionResponse {
        if (!resetNickname && !resetProfileImage) throw BusinessException(ErrorCode.INVALID_REQUEST)
        val member = getMember(memberId)
        val before = mapOf("nickname" to member.nickname, "profileImageUrl" to member.profileImageUrl)
        if (resetNickname) member.resetNickname()
        if (resetProfileImage) member.resetProfileImage()
        auditRecorder.record(
            adminId, AdminAuditAction.MEMBER_PROFILE_RESET, AdminAuditTargetType.MEMBER, member.id,
            before, mapOf("nickname" to member.nickname, "profileImageUrl" to member.profileImageUrl),
        )
        return toActionResponse(member)
    }

    @Transactional
    fun unlockScan(adminId: Long, memberId: Long): AdminMemberActionResponse {
        val member = getMember(memberId)
        val before = member.scanUnlocked
        member.unlockScan()
        auditRecorder.record(
            adminId, AdminAuditAction.MEMBER_SCAN_UNLOCK, AdminAuditTargetType.MEMBER, member.id,
            mapOf("scanUnlocked" to before), mapOf("scanUnlocked" to true),
        )
        return toActionResponse(member)
    }

    fun withdraw(adminId: Long, memberId: Long): AdminMemberActionResponse {
        val member = memberRepository.findByIdIncludingWithdrawn(memberId) ?: throw BusinessException(ErrorCode.MEMBER_NOT_FOUND)
        if (member.isDeleted()) return toActionResponse(member)
        try {
            socialAccountDeleter.delete(member.provider, member.providerUid)
        } catch (e: Exception) {
            log.error("관리자 강제 탈퇴 — 소셜 계정 삭제 실패 memberId={}", memberId, e)
            throw BusinessException(ErrorCode.SOCIAL_ACCOUNT_DELETE_FAILED)
        }
        return transaction.execute {
            val managed = memberRepository.findById(memberId).orElse(null)
            if (managed != null) {
                managed.withdraw()
                auditRecorder.record(adminId, AdminAuditAction.MEMBER_WITHDRAW, AdminAuditTargetType.MEMBER, memberId, null, null)
            }
            toActionResponse(managed ?: member)
        }!!
    }

    private fun getMember(memberId: Long): Member =
        memberRepository.findById(memberId).orElseThrow { BusinessException(ErrorCode.MEMBER_NOT_FOUND) }

    private fun toActionResponse(member: Member) = AdminMemberActionResponse(
        id = member.id,
        memberStatus = member.memberStatus,
        nickname = member.nickname,
        profileImageUrl = member.profileImageUrl,
        scanUnlocked = member.scanUnlocked,
        withdrawn = member.isDeleted(),
    )

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
                email = AdminMemberService.maskEmail(member.email),
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
                email = AdminMemberService.maskEmail(member.email),
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
