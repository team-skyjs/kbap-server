package com.meogo.domain.scan

import com.meogo.core.id.FoodId
import com.meogo.core.id.MemberId
import com.meogo.core.stereotype.AggregateRoot

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
