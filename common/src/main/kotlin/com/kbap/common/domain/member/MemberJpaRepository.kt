package com.kbap.common.domain.member

import com.kbap.common.domain.member.model.Member
import com.kbap.common.domain.member.model.MemberStatus
import com.kbap.common.domain.member.model.SocialProvider
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface MemberJpaRepository : JpaRepository<Member, Long> {
    fun findByIdAndMemberStatus(id: Long, memberStatus: MemberStatus): Member?

    fun findByProviderAndProviderUidAndMemberStatus(
        provider: SocialProvider,
        providerUid: String,
        memberStatus: MemberStatus,
    ): Member?

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update Member m
        set m.scanCount = m.scanCount + 1
        where m.id = :memberId
          and m.memberStatus = com.kbap.common.domain.member.model.MemberStatus.ACTIVE
          and m.status = com.kbap.common.domain.EntityStatus.ACTIVE
        """,
    )
    fun increaseScanCount(@Param("memberId") memberId: Long): Int
}
