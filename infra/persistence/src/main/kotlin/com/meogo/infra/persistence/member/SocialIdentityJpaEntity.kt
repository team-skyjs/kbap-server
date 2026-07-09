package com.meogo.infra.persistence.member

import com.meogo.core.member.SocialIdentity
import com.meogo.core.member.SocialProvider
import com.meogo.infra.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

@Entity
@Table(
    name = "member_social_identities",
    uniqueConstraints = [UniqueConstraint(columnNames = ["provider", "provider_user_id"])],
)
class SocialIdentityJpaEntity(
    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, columnDefinition = "ENUM('GOOGLE','APPLE')")
    var provider: SocialProvider = SocialProvider.GOOGLE,

    @Column(name = "provider_user_id", nullable = false, length = 255)
    var providerUserId: String = "",

    @Column(name = "email", length = 255)
    var email: String? = null,
) : BaseEntity() {
    fun toDomain(): SocialIdentity =
        SocialIdentity(
            provider = provider,
            providerUserId = providerUserId,
            email = email,
        )

    companion object {
        fun from(domain: SocialIdentity): SocialIdentityJpaEntity =
            SocialIdentityJpaEntity(
                provider = domain.provider,
                providerUserId = domain.providerUserId,
                email = domain.email,
            )
    }
}
