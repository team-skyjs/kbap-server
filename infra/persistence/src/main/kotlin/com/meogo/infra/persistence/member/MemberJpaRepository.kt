package com.meogo.infra.persistence.member

import com.meogo.core.member.SocialProvider
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface MemberJpaRepository : JpaRepository<MemberJpaEntity, Long> {
    @Query(
        """
        select distinct m from MemberJpaEntity m
        left join fetch m.identities
        where m.id = :id
        """,
    )
    fun findByIdWithIdentities(@Param("id") id: Long): MemberJpaEntity?

    @Query(
        """
        select distinct m from MemberJpaEntity m
        join m.identities i
        left join fetch m.identities
        where i.provider = :provider and i.providerUserId = :providerUserId
        """,
    )
    fun findByIdentity(
        @Param("provider") provider: SocialProvider,
        @Param("providerUserId") providerUserId: String,
    ): MemberJpaEntity?
}
