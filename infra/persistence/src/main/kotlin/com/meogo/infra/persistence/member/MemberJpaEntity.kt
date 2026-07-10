package com.meogo.infra.persistence.member

import com.meogo.core.kernel.lang.LanguageCode
import com.meogo.core.member.AvoidanceSubstanceCodeRef
import com.meogo.core.member.Member
import com.meogo.core.member.MemberProfile
import com.meogo.core.member.OnboardingStatus
import com.meogo.infra.persistence.BaseEntity
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(name = "members")
class MemberJpaEntity(
    @Column(name = "nickname", length = 30)
    var nickname: String? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "avoidance_substance_codes", nullable = false)
    var avoidanceSubstanceCodes: List<String> = emptyList(),

    @Column(name = "spiciness_preference")
    var spicinessPreference: Int? = null,

    @Column(name = "country_code", length = 2)
    var countryCode: String? = null,

    @Column(name = "app_language", length = 10)
    var appLanguage: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "onboarding_status", nullable = false, columnDefinition = "ENUM('PENDING','COMPLETED')")
    var onboardingStatus: OnboardingStatus = OnboardingStatus.PENDING,

    @OneToMany(cascade = [CascadeType.ALL], fetch = FetchType.LAZY, orphanRemoval = true)
    @JoinColumn(name = "member_id", nullable = false)
    var identities: MutableList<SocialIdentityJpaEntity> = mutableListOf(),
) : BaseEntity() {
    fun toDomain(): Member =
        Member.reconstitute(
            id = id,
            identities = identities.map { it.toDomain() },
            profile = MemberProfile(
                nickname = nickname,
                avoidanceSubstanceCodes = avoidanceSubstanceCodes.map { AvoidanceSubstanceCodeRef(it) }.toSet(),
                spicinessPreference = spicinessPreference,
                countryCode = countryCode,
                appLanguage = appLanguage?.let { code -> LanguageCode.entries.firstOrNull { it.code == code } },
            ),
            onboardingStatus = onboardingStatus,
        )

    fun applyProfile(domain: Member) {
        nickname = domain.profile.nickname
        avoidanceSubstanceCodes = domain.profile.avoidanceSubstanceCodes.map { it.value }
        spicinessPreference = domain.profile.spicinessPreference
        countryCode = domain.profile.countryCode
        appLanguage = domain.profile.appLanguage?.code
        onboardingStatus = domain.onboardingStatus
    }

    companion object {
        fun from(domain: Member): MemberJpaEntity =
            MemberJpaEntity(
                identities = domain.identities.map { SocialIdentityJpaEntity.from(it) }.toMutableList(),
            ).apply { applyProfile(domain) }
    }
}
