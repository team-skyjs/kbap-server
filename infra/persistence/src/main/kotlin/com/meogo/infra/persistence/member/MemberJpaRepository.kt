package com.meogo.infra.persistence.member

import com.meogo.core.member.SocialProvider
import org.springframework.data.jpa.repository.JpaRepository

interface MemberJpaRepository : JpaRepository<MemberJpaEntity, Long> {
    fun findByIdAndMemberStatus(id: Long, memberStatus: MemberStatus): MemberJpaEntity?

    fun findByProviderAndProviderUidAndMemberStatus(
        provider: SocialProvider,
        providerUid: String,
        memberStatus: MemberStatus,
    ): MemberJpaEntity?
}
