package com.meogo.application.client.member

import com.meogo.core.member.MemberRankingRepository

class FakeMemberRankingRepository : MemberRankingRepository {
    private val scanCounts = mutableMapOf<Long, Int>()

    override fun increaseScanCount(memberId: Long) {
        scanCounts[memberId] = (scanCounts[memberId] ?: 0) + 1
    }

    override fun scanCountOf(memberId: Long): Int = scanCounts[memberId] ?: 0
}
