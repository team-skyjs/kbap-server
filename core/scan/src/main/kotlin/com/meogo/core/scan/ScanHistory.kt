package com.meogo.core.scan

import com.meogo.core.kernel.stereotype.AggregateRoot

@AggregateRoot
class ScanHistory private constructor(
    val id: Long?,
    val memberId: Long,
    val foodId: Long,
) {
    companion object {
        fun record(memberId: Long, foodId: Long): ScanHistory =
            ScanHistory(id = null, memberId = memberId, foodId = foodId)
    }
}
