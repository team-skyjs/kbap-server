package com.meogo.infra.persistence.member

import com.meogo.core.member.MemberRankingRepository
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
class MemberRankingRepositoryAdapter(
    private val memberRankingJpaRepository: MemberRankingJpaRepository,
) : MemberRankingRepository {
    @Transactional
    override fun increaseScanCount(memberId: Long) {
        memberRankingJpaRepository.increaseScanCount(memberId)
    }

    @Transactional(readOnly = true)
    override fun scanCountOf(memberId: Long): Int =
        memberRankingJpaRepository.findScanCountByMemberId(memberId) ?: 0
}
