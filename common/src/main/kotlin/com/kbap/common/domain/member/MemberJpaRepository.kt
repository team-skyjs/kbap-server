package com.kbap.common.domain.member

import com.kbap.common.domain.member.model.Member
import com.kbap.common.domain.member.model.MemberStatus
import com.kbap.common.domain.member.model.SocialProvider
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface MemberJpaRepository : JpaRepository<Member, Long> {
    fun findByIdAndMemberStatus(id: Long, memberStatus: MemberStatus): Member?

    @Query(
        """
        select m from Member m
        where m.id = :memberId
           or m.nickname like concat('%', :keyword, '%')
           or m.email like concat('%', :keyword, '%')
        """,
    )
    fun searchByKeyword(
        @Param("keyword") keyword: String,
        @Param("memberId") memberId: Long,
        pageable: Pageable,
    ): Page<Member>

    fun countByMemberStatus(memberStatus: MemberStatus): Long

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
        """,
    )
    fun increaseScanCount(@Param("memberId") memberId: Long): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update Member m
        set m.reviewCount = m.reviewCount + 1, m.scanUnlocked = true
        where m.id = :memberId
          and m.memberStatus = com.kbap.common.domain.member.model.MemberStatus.ACTIVE
        """,
    )
    fun increaseReviewCount(@Param("memberId") memberId: Long): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update Member m
        set m.reviewCount = m.reviewCount - 1
        where m.id = :memberId
          and m.reviewCount > 0
          and m.memberStatus = com.kbap.common.domain.member.model.MemberStatus.ACTIVE
        """,
    )
    fun decreaseReviewCount(@Param("memberId") memberId: Long): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update Member m
        set m.uniqueReviewedFoodCount = m.uniqueReviewedFoodCount + 1
        where m.id = :memberId
          and m.memberStatus = com.kbap.common.domain.member.model.MemberStatus.ACTIVE
        """,
    )
    fun increaseUniqueReviewedFoodCount(@Param("memberId") memberId: Long): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update Member m
        set m.uniqueReviewedFoodCount = m.uniqueReviewedFoodCount - 1
        where m.id = :memberId
          and m.uniqueReviewedFoodCount > 0
          and m.memberStatus = com.kbap.common.domain.member.model.MemberStatus.ACTIVE
        """,
    )
    fun decreaseUniqueReviewedFoodCount(@Param("memberId") memberId: Long): Int
}
