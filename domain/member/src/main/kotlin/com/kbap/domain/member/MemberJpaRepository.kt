package com.kbap.domain.member

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

internal interface MemberJpaRepository : JpaRepository<MemberJpaEntity, Long> {
    fun findByIdAndMemberStatus(id: Long, memberStatus: MemberStatus): MemberJpaEntity?

    fun findByProviderAndProviderUidAndMemberStatus(
        provider: SocialProvider,
        providerUid: String,
        memberStatus: MemberStatus,
    ): MemberJpaEntity?

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update MemberJpaEntity m
        set m.scanCount = m.scanCount + 1
        where m.id = :memberId
          and m.memberStatus = com.kbap.domain.member.MemberStatus.ACTIVE
          and m.status = com.kbap.core.persistence.EntityStatus.ACTIVE
        """,
    )
    fun increaseScanCount(@Param("memberId") memberId: Long): Int
}
