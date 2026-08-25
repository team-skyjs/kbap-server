package com.kbap.common.domain.member

import com.kbap.common.domain.member.model.MemberRankingEvent
import com.kbap.common.domain.member.model.RankingEventType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface MemberRankingEventJpaRepository : JpaRepository<MemberRankingEvent, Long> {
    fun existsByReviewIdAndEvent(reviewId: Long, event: RankingEventType): Boolean

    fun findByMemberIdOrderByIdDesc(memberId: Long, pageable: Pageable): Page<MemberRankingEvent>
}
