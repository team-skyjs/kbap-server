package com.kbap.domain.scan

import com.kbap.core.id.FoodId
import com.kbap.core.id.MemberId
import com.kbap.core.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Index
import jakarta.persistence.Table

@Entity
@Table(
    name = "scan_history",
    indexes = [Index(name = "idx_scan_history_recent", columnList = "member_id, created_at")],
)
class ScanHistory(
    @Column(name = "member_id", nullable = false)
    var memberId: MemberId = MemberId(0),

    @Column(name = "food_id", nullable = false)
    var foodId: FoodId = FoodId(0),
) : BaseEntity() {
    constructor() : this(MemberId(0), FoodId(0))

    companion object {
        fun record(memberId: MemberId, foodId: FoodId): ScanHistory =
            ScanHistory(memberId = memberId, foodId = foodId)
    }
}
