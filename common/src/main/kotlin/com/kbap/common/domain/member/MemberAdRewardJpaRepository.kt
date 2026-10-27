package com.kbap.common.domain.member

import com.kbap.common.domain.member.dto.AdRewardCounts
import com.kbap.common.domain.member.model.MemberAdReward
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface MemberAdRewardJpaRepository : JpaRepository<MemberAdReward, Long> {
    fun findByRewardKey(rewardKey: String): MemberAdReward?

    fun countByMemberId(memberId: Long): Long

    @Modifying(flushAutomatically = true)
    @Query(
        nativeQuery = true,
        value = "UPDATE member_ad_reward SET status = 'DELETED', updated_at = NOW(6) WHERE member_id = :memberId AND status = 'ACTIVE'",
    )
    fun deleteAllByMemberId(@Param("memberId") memberId: Long): Int

    @Query(
        """
        select new com.kbap.common.domain.member.dto.AdRewardCounts(
            count(r),
            coalesce(sum(case when r.createdAt >= :since then 1L else 0L end), 0L)
        )
        from MemberAdReward r
        where r.memberId = :memberId
        """,
    )
    fun countTotalAndRecent(@Param("memberId") memberId: Long, @Param("since") since: LocalDateTime): AdRewardCounts
}
