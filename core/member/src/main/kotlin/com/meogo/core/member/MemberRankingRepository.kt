package com.meogo.core.member

interface MemberRankingRepository {
    fun increaseScanCount(memberId: Long)

    fun scanCountOf(memberId: Long): Int
}
