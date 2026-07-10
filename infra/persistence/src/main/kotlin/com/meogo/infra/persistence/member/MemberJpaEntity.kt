package com.meogo.infra.persistence.member

import com.meogo.core.member.Member
import com.meogo.core.member.OnboardingStatus
import com.meogo.core.member.SocialIdentity
import com.meogo.core.member.SocialProvider
import com.meogo.infra.persistence.BaseEntity
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
class MemberJpaEntity(
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

    @Column(name = "onboarding_status", nullable = false)
    var onboardingCompleted: Boolean = false,
) : BaseEntity() {
    fun toDomain(): Member =
        Member.reconstitute(
            id = id,
            identity = SocialIdentity(provider = provider, providerUserId = providerUid, email = email),
            profile = profile.toDomain(nickname),
            onboardingStatus = if (onboardingCompleted) OnboardingStatus.COMPLETED else OnboardingStatus.PENDING,
        )

    fun applyProfile(domain: Member) {
        nickname = domain.profile.nickname
        profile = MemberProfileJson.from(domain.profile)
        onboardingCompleted = domain.onboardingStatus == OnboardingStatus.COMPLETED
    }

    fun withdraw() {
        providerUid = withdrawnProviderUid(id)
        email = null
        delete()
    }

    companion object {
        private fun withdrawnProviderUid(memberId: Long): String = "withdrawn:$memberId"

        fun from(domain: Member): MemberJpaEntity =
            MemberJpaEntity(
                provider = domain.identity.provider,
                providerUid = domain.identity.providerUserId,
                email = domain.identity.email,
            ).apply { applyProfile(domain) }
    }
}
