package com.kbap.api.admin

import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.member.model.Member
import com.kbap.common.domain.member.model.MemberStatus
import com.kbap.common.domain.member.model.SocialProvider
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

    companion object {
        const val PAGE_SIZE = 20
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
