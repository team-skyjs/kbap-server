package com.kbap.common.domain.member

import com.kbap.common.domain.member.model.MemberAdReward
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDateTime

interface MemberAdRewardJpaRepository : JpaRepository<MemberAdReward, Long> {
    fun findByRewardKey(rewardKey: String): MemberAdReward?

    fun countByMemberId(memberId: Long): Long

    fun countByMemberIdAndCreatedAtGreaterThanEqual(memberId: Long, since: LocalDateTime): Long
}
