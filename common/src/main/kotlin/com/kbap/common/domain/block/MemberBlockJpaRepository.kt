package com.kbap.common.domain.block

import com.kbap.common.domain.block.model.MemberBlock
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface MemberBlockJpaRepository : JpaRepository<MemberBlock, Long> {
    // 신규·중복·재차단(부활)을 한 문장으로 원자 처리 — 트랜잭션 안 save() 예외 폴백은 rollback-only 마킹으로 불가
    @Modifying
    @Query(
        value = """
            INSERT INTO member_block (blocker_member_id, blocked_member_id, status, created_at, updated_at)
            VALUES (:blockerMemberId, :blockedMemberId, 'ACTIVE', NOW(6), NOW(6))
            ON DUPLICATE KEY UPDATE status = 'ACTIVE', updated_at = NOW(6)
        """,
        nativeQuery = true,
    )
    fun upsertActive(
        @Param("blockerMemberId") blockerMemberId: Long,
        @Param("blockedMemberId") blockedMemberId: Long,
    )

    @Query("select b.blockedMemberId from MemberBlock b where b.blockerMemberId = :blockerMemberId")
    fun findBlockedMemberIds(@Param("blockerMemberId") blockerMemberId: Long): List<Long>

    fun findByBlockerMemberIdAndBlockedMemberId(blockerMemberId: Long, blockedMemberId: Long): MemberBlock?

    fun countByBlockerMemberId(blockerMemberId: Long): Long

    // 상태 무시 native 조회 — @SQLRestriction(ACTIVE)을 우회해 DELETED 행을 찾아 재차단 시 부활시킨다.
    @Query(
        value = "SELECT * FROM member_block WHERE blocker_member_id = :blockerMemberId AND blocked_member_id = :blockedMemberId LIMIT 1",
        nativeQuery = true,
    )
    fun findAnyByPair(
        @Param("blockerMemberId") blockerMemberId: Long,
        @Param("blockedMemberId") blockedMemberId: Long,
    ): MemberBlock?
}
