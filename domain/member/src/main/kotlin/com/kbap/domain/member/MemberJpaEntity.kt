package com.kbap.domain.member

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
internal class MemberJpaEntity(
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
    var profile: MemberProfileJson = MemberProfileJson(),

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
    fun toDomain(): Member =
        Member.reconstitute(
            id = id,
            identity = SocialIdentity(provider = provider, providerUserId = providerUid, email = email),
            profile = profile.toDomain(nickname),
            onboardingCompleted = onboardingCompleted,
            ranking = Ranking.of(
                scanCount = scanCount,
                reviewCount = reviewCount,
                uniqueReviewedFoodCount = uniqueReviewedFoodCount,
            ),
        )

    fun applyDomain(domain: Member) {
        nickname = domain.profile.nickname
        profile = MemberProfileJson.from(domain.profile)
        onboardingCompleted = domain.onboardingCompleted
    }

    fun withdraw() {
        providerUid = deletedProviderUid(id)
        delete()
    }

    companion object {
        const val DELETED_PROVIDER_UID_PREFIX: String = "DELETED:"

        private fun deletedProviderUid(memberId: Long): String = "$DELETED_PROVIDER_UID_PREFIX$memberId"

        fun from(domain: Member): MemberJpaEntity =
            MemberJpaEntity(
                provider = domain.identity.provider,
                providerUid = domain.identity.providerUserId,
                email = domain.identity.email,
            ).apply { applyDomain(domain) }
    }
}
