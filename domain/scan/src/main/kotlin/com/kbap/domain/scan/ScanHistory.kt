package com.kbap.domain.scan

import com.kbap.core.id.FoodId
import com.kbap.core.id.MemberId
import com.kbap.core.stereotype.AggregateRoot

@AggregateRoot
class ScanHistory private constructor(
    val id: Long?,
    val memberId: MemberId,
    val foodId: FoodId,
) {
    companion object {
        fun record(memberId: MemberId, foodId: FoodId): ScanHistory =
            ScanHistory(id = null, memberId = memberId, foodId = foodId)
    }
}
