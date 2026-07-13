package com.kbap.domain.member

import com.kbap.core.error.ErrorCode
import com.kbap.core.error.BusinessException
import com.kbap.core.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(
    name = "member",
    uniqueConstraints = [UniqueConstraint(name = "uk_member_provider_uid", columnNames = ["provider", "provider_uid"])],
)
class Member(
    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, columnDefinition = "ENUM('GOOGLE','APPLE')")
    var provider: SocialProvider = SocialProvider.GOOGLE,

    @Column(name = "provider_uid", nullable = false, length = 255)
    var providerUid: String = "",

    @Column(name = "email", length = 255)
    var email: String? = null,

    @Column(name = "nickname", length = 30)
    var nickname: String? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "profile", nullable = false)
    var profileJson: MemberProfileJson = MemberProfileJson(),

    @Enumerated(EnumType.STRING)
    @Column(name = "member_status", nullable = false, columnDefinition = "ENUM('ACTIVE','SUSPENDED') default 'ACTIVE'")
    var memberStatus: MemberStatus = MemberStatus.ACTIVE,

    @Column(name = "onboarding_completed", nullable = false)
    var onboardingCompleted: Boolean = false,

    @Column(name = "scan_count", nullable = false)
    var scanCount: Int = 0,

    @Column(name = "review_count", nullable = false)
    var reviewCount: Int = 0,

    @Column(name = "unique_reviewed_food_count", nullable = false)
    var uniqueReviewedFoodCount: Int = 0,
) : BaseEntity() {
    val identity: SocialIdentity
        get() = SocialIdentity(provider = provider, providerUserId = providerUid, email = email)

    val profile: MemberProfile
        get() = profileJson.toDomain(nickname)

    val ranking: Ranking
        get() = Ranking.of(
            scanCount = scanCount,
            reviewCount = reviewCount,
            uniqueReviewedFoodCount = uniqueReviewedFoodCount,
        )

    fun updateProfile(profile: MemberProfile) {
        nickname = profile.nickname
        profileJson = MemberProfileJson.from(profile)
    }

    fun completeOnboarding() {
        if (onboardingCompleted) {
            throw BusinessException(ErrorCode.ONBOARDING_ALREADY_COMPLETED)
        }
        onboardingCompleted = true
    }

    fun withdraw() {
        providerUid = deletedProviderUid(id)
        delete()
    }

    companion object {
        const val DELETED_PROVIDER_UID_PREFIX: String = "DELETED:"

        private fun deletedProviderUid(memberId: Long): String = "$DELETED_PROVIDER_UID_PREFIX$memberId"

        fun signUp(identity: SocialIdentity): Member =
            Member(
                provider = identity.provider,
                providerUid = identity.providerUserId,
                email = identity.email,
            )
    }
}
