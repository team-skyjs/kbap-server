package com.kbap.common.domain.block

import com.kbap.common.domain.block.model.MemberBlock
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface MemberBlockJpaRepository : JpaRepository<MemberBlock, Long> {
    @Query("select b.blockedMemberId from MemberBlock b where b.blockerMemberId = :blockerMemberId")
    fun findBlockedMemberIds(@Param("blockerMemberId") blockerMemberId: Long): List<Long>

    fun findByBlockerMemberIdAndBlockedMemberId(blockerMemberId: Long, blockedMemberId: Long): MemberBlock?

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
