package com.kbap.common.domain.member.model

import com.kbap.common.core.error.ErrorCode
import com.kbap.common.core.error.BusinessException
import com.kbap.common.domain.CurrencyCode
import com.kbap.common.domain.ingredient.model.DietCategory
import com.kbap.common.domain.BaseEntity
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

    @Enumerated(EnumType.STRING)
    @Column(name = "spiciness_preference", nullable = false, columnDefinition = "ENUM('SKIP','NONE','MILD','MEDIUM','HOT','EXTREME') default 'SKIP'")
    var spicinessPreference: SpicinessPreference = SpicinessPreference.SKIP,

    @Column(name = "country_code", length = 2)
    var countryCode: String? = null,

    @Column(name = "profile_image_url", length = 512)
    var profileImageUrl: String? = null,

    @Column(name = "currency", length = 3)
    var currency: String? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "avoidance_substance_codes", nullable = false)
    var avoidanceSubstanceCodes: List<String> = emptyList(),

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "diet_categories", nullable = false)
    var dietCategories: List<String> = emptyList(),

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
        get() = MemberProfile.of(
            nickname = nickname,
            avoidanceSubstanceCodes = avoidanceSubstanceCodes.map { AvoidedIngredientCodeRef(it) }.toSet(),
            spicinessPreference = spicinessPreference,
            countryCode = CountryCode.from(countryCode),
            profileImageUrl = profileImageUrl,
            currency = CurrencyCode.from(currency),
            dietCategories = dietCategories.mapNotNull { code -> DietCategory.entries.firstOrNull { it.name == code } }.toSet(),
        )

    val ranking: Ranking
        get() = Ranking.of(
            scanCount = scanCount,
            reviewCount = reviewCount,
            uniqueReviewedFoodCount = uniqueReviewedFoodCount,
        )

    internal fun updateProfile(profile: MemberProfile) {
        nickname = profile.nickname
        spicinessPreference = profile.spicinessPreference
        countryCode = profile.countryCode?.name
        profileImageUrl = profile.profileImageUrl
        currency = profile.currency?.name
        avoidanceSubstanceCodes = profile.avoidanceSubstanceCodes.map { it.value }
        dietCategories = profile.dietCategories.map { it.name }
    }

    fun updateProfile(
        nickname: String? = null,
        avoidanceSubstanceCodes: List<String>? = null,
        dietCategories: List<String>? = null,
        spicinessPreference: String? = null,
        countryCode: String? = null,
        profileImageUrl: String? = null,
        currency: String? = null,
    ) {
        updateProfile(
            profile.updatedWith(
                nickname = nickname,
                avoidanceSubstanceCodes = avoidanceSubstanceCodes,
                dietCategories = dietCategories,
                spicinessPreference = spicinessPreference,
                countryCode = countryCode,
                profileImageUrl = profileImageUrl,
                currency = currency,
            ),
        )
    }

    fun completeOnboarding(
        nickname: String,
        avoidanceSubstanceCodes: List<String>,
        dietCategories: List<String> = emptyList(),
        spicinessPreference: String,
        countryCode: String,
        profileImageUrl: String,
    ) {
        if (onboardingCompleted) {
            throw BusinessException(ErrorCode.ONBOARDING_ALREADY_COMPLETED)
        }
        updateProfile(
            nickname = nickname,
            avoidanceSubstanceCodes = avoidanceSubstanceCodes,
            dietCategories = dietCategories,
            spicinessPreference = spicinessPreference,
            countryCode = countryCode,
            profileImageUrl = profileImageUrl,
            currency = CountryCode.from(countryCode)?.currency?.name,
        )
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
