package com.kbap.common.domain.member

import com.kbap.common.domain.member.dto.NewMemberRow
import com.kbap.common.domain.member.model.Member
import com.kbap.common.domain.member.model.MemberStatus
import com.kbap.common.domain.member.model.SocialProvider
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface MemberJpaRepository : JpaRepository<Member, Long> {
    fun findByIsBotTrueAndMemberStatus(memberStatus: MemberStatus): List<Member>

    @Query(
        value = "SELECT * FROM member ORDER BY id DESC",
        countQuery = "SELECT count(*) FROM member",
        nativeQuery = true,
    )
    fun findPageAnyStatus(pageable: Pageable): Page<Member>

    @Query(
        value = """
            SELECT * FROM member
            WHERE id = :memberId
               OR nickname LIKE CONCAT('%', :keyword, '%') ESCAPE '\\'
               OR email LIKE CONCAT('%', :keyword, '%') ESCAPE '\\'
            ORDER BY id DESC
        """,
        countQuery = """
            SELECT count(*) FROM member
            WHERE id = :memberId
               OR nickname LIKE CONCAT('%', :keyword, '%') ESCAPE '\\'
               OR email LIKE CONCAT('%', :keyword, '%') ESCAPE '\\'
        """,
        nativeQuery = true,
    )
    fun searchPageAnyStatusByKeyword(
        @Param("keyword") keyword: String,
        @Param("memberId") memberId: Long,
        pageable: Pageable,
    ): Page<Member>

    @Query(value = "SELECT * FROM member WHERE id = :id", nativeQuery = true)
    fun findAnyById(@Param("id") id: Long): Member?

    fun countByMemberStatusAndIsBotFalse(memberStatus: MemberStatus): Long

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

    @Query(
        nativeQuery = true,
        value = """
            SELECT m.created_at AS createdAt,
                   m.country_code AS countryCode,
                   (SELECT d.platform FROM notification_device d
                    WHERE d.member_id = m.id ORDER BY d.id DESC LIMIT 1) AS platform
            FROM member m
            WHERE m.created_at >= :from AND m.created_at < :to AND $REAL_MEMBER
        """,
    )
    fun findRealMembersCreatedBetween(
        @Param("from") from: LocalDateTime,
        @Param("to") to: LocalDateTime,
        @Param("excludedIds") excludedIds: Collection<Long>,
    ): List<NewMemberRow>

    @Query(
        nativeQuery = true,
        value = "SELECT COUNT(*) FROM member m WHERE m.member_status = 'ACTIVE' AND m.status = 'ACTIVE' AND $REAL_MEMBER",
    )
    fun countActiveRealMembers(@Param("excludedIds") excludedIds: Collection<Long>): Long

    companion object {
        const val REAL_MEMBER =
            "(m.email IS NULL OR (m.email NOT REGEXP '^[a-z]+\\\\.[0-9]{5}@gmail\\\\.com$' " +
                "AND m.email NOT REGEXP '@cloudtestlabaccounts\\\\.com$')) " +
                "AND m.id NOT IN (:excludedIds)"
    }
}
